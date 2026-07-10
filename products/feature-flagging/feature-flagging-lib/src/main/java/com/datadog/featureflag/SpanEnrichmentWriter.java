package com.datadog.featureflag;

import datadog.trace.api.GlobalTracer;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.SpanEnrichmentEvent;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent-side owner of APM feature-flag span enrichment. This is the WRITE tier of the
 * capture-vs-write split: it listens on {@link FeatureFlaggingGateway} for {@link
 * SpanEnrichmentEvent}s dispatched by the published {@code dd-openfeature} provider during flag
 * evaluation, resolves the active local-root span, and accumulates per-trace state that a {@link
 * SpanEnrichmentInterceptor} later flushes onto the root when the trace completes.
 *
 * <p><b>Process-wide singleton (restart-safe).</b> Use {@link #getInstance()} for the agent wiring.
 * The tracer keeps trace interceptors for the life of the JVM and offers no removal API, so the
 * interceptor — and the weak-keyed state it reads — must outlive any single start/stop of the
 * feature-flagging subsystem. A fresh writer per {@code start()} would build a second interceptor
 * at the same priority; the tracer would reject it and its state would never be read, silently
 * disabling enrichment after a restart. Reusing one instance avoids that: the single interceptor is
 * registered exactly once and simply resumes when {@link #init()} re-subscribes the listener.
 *
 * <p><b>Zero idle overhead when off.</b> When the span-enrichment gate is off the provider adds no
 * capture hook, so no seam events are dispatched, this listener never runs, and the interceptor is
 * never registered — the tracer's write path is untouched. The interceptor is registered lazily on
 * the first enrichment event that has an active span, so a service that enables the feature but
 * never evaluates a flag on a traced request still pays nothing.
 *
 * <p>All work is wrapped in try/catch — enrichment must NEVER break flag evaluation.
 */
public final class SpanEnrichmentWriter implements FeatureFlaggingGateway.SpanEnrichmentListener {

  private static final Logger log = LoggerFactory.getLogger(SpanEnrichmentWriter.class);

  // The one instance used by the agent. Persisting it across FeatureFlaggingSystem start/stop keeps
  // the single registered interceptor (and its state) alive, so a restart never re-registers.
  private static final SpanEnrichmentWriter INSTANCE = new SpanEnrichmentWriter();

  public static SpanEnrichmentWriter getInstance() {
    return INSTANCE;
  }

  /**
   * Resolves the local-root span for the active trace. Injectable so tests need no static mocks.
   */
  interface RootSpanResolver {
    AgentSpan activeLocalRoot();
  }

  /**
   * Registers the interceptor with the tracer, returning {@code true} when accepted. Injectable so
   * tests are deterministic without a globally-installed tracer.
   */
  interface InterceptorRegistrar {
    boolean register(SpanEnrichmentInterceptor interceptor);
  }

  private static final RootSpanResolver DEFAULT_RESOLVER =
      () -> {
        final AgentSpan active = AgentTracer.activeSpan();
        if (active == null) {
          return null;
        }
        final AgentSpan localRoot = active.getLocalRootSpan();
        return localRoot != null ? localRoot : active;
      };

  private static final InterceptorRegistrar DEFAULT_REGISTRAR =
      interceptor -> GlobalTracer.get().addTraceInterceptor(interceptor);

  private final RootSpanResolver rootSpanResolver;
  private final InterceptorRegistrar registrar;
  private final SpanEnrichmentStates states;
  private final SpanEnrichmentInterceptor interceptor;
  // Registered with the tracer at most once, lazily on the first enrichment event with an active
  // span. Once true it stays true for the life of this instance, so a subsystem restart (which
  // reuses the singleton) never attempts a second, doomed registration.
  private final AtomicBoolean interceptorRegistered = new AtomicBoolean(false);

  private SpanEnrichmentWriter() {
    this(DEFAULT_RESOLVER, DEFAULT_REGISTRAR);
  }

  // Visible for tests: an isolated writer (own state + interceptor) whose interceptor registration
  // is assumed to succeed, bypassing the shared INSTANCE and any globally-installed tracer.
  SpanEnrichmentWriter(final RootSpanResolver rootSpanResolver) {
    this(rootSpanResolver, interceptor -> true);
  }

  // Visible for tests: also inject the registrar to exercise the not-registered path.
  SpanEnrichmentWriter(
      final RootSpanResolver rootSpanResolver, final InterceptorRegistrar registrar) {
    this.rootSpanResolver = rootSpanResolver;
    this.registrar = registrar;
    this.states = new SpanEnrichmentStates();
    this.interceptor = new SpanEnrichmentInterceptor(states);
  }

  /** Starts listening for enrichment events. Safe to call again after {@link #close()}. */
  public void init() {
    FeatureFlaggingGateway.addSpanEnrichmentListener(this);
  }

  /**
   * Stops listening and drops any residual state. The interceptor stays registered with the tracer
   * (it cannot be removed) but goes inert while the state is empty; a later {@link #init()} resumes
   * enrichment on the same interceptor.
   */
  public void close() {
    FeatureFlaggingGateway.removeSpanEnrichmentListener(this);
    states.clear();
  }

  @Override
  public void accept(final SpanEnrichmentEvent event) {
    if (event == null) {
      return;
    }
    try {
      final AgentSpan root = rootSpanResolver.activeLocalRoot();
      if (root == null) {
        return; // no active span → nothing to enrich (and nothing to register the interceptor for)
      }
      if (!ensureInterceptorRegistered()) {
        // The interceptor isn't registered (e.g. tracer absent), so nothing would ever flush this
        // state — skip accumulating. A later event retries registration.
        return;
      }
      final SpanEnrichmentAccumulator state = states.getOrCreate(root);
      if (event.hasSerialId()) {
        final int serialId = event.serialId();
        state.addSerialId(serialId);
        if (event.doLog() && event.targetingKey() != null) {
          state.addSubject(event.targetingKey(), serialId);
        }
      } else if (event.flagKey() != null) {
        state.addDefault(event.flagKey(), event.defaultValue());
      }
    } catch (final Throwable t) {
      // Never let span enrichment break flag evaluation; a debug line aids diagnosis if it does.
      log.debug("Span-enrichment accumulation failed", t);
    }
  }

  /**
   * @return true once the interceptor is registered with the tracer.
   */
  private boolean ensureInterceptorRegistered() {
    if (interceptorRegistered.get()) {
      return true;
    }
    synchronized (this) {
      if (interceptorRegistered.get()) {
        return true;
      }
      try {
        // register() returns false (without throwing) when the tracer rejects it — e.g. the global
        // tracer is still the no-op placeholder. Only latch on success so a later event retries;
        // otherwise a transient false would permanently disable enrichment.
        if (registrar.register(interceptor)) {
          interceptorRegistered.set(true);
        }
      } catch (final Throwable t) {
        // Leave unregistered; a later event retries.
      }
      return interceptorRegistered.get();
    }
  }

  // ---- test-only accessors ----

  SpanEnrichmentStates states() {
    return states;
  }

  SpanEnrichmentInterceptor interceptor() {
    return interceptor;
  }
}

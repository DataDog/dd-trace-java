package com.datadog.featureflag;

import datadog.trace.api.GlobalTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent-side owner of APM feature-flag span enrichment. This is the WRITE tier of the
 * capture-vs-write split: it receives the evaluations captured by the {@code dd-openfeature} SDK
 * through its instrumentation, resolves the active local-root span, and accumulates per-trace state
 * that a {@link SpanEnrichmentInterceptor} later flushes onto the root when the trace completes.
 *
 * <p><b>Process-wide singleton (restart-safe).</b> Use {@link #getInstance()} for the agent wiring.
 * The tracer keeps trace interceptors for the life of the JVM and offers no removal API, so the
 * interceptor — and the weak-keyed state it reads — must outlive any single start/stop of the
 * feature-flagging subsystem. A fresh writer per {@code start()} would build a second interceptor
 * at the same priority; the tracer would reject it and its state would never be read, silently
 * disabling enrichment after a restart. Reusing one instance avoids that: the single interceptor is
 * registered exactly once and simply resumes on the next recorded evaluation.
 *
 * <p><b>Zero idle overhead when off.</b> When the span-enrichment gate is off the provider adds no
 * capture hook, so no evaluation is recorded, this writer never runs, and the interceptor is never
 * registered — the tracer's write path is untouched. The interceptor is registered lazily on the
 * first enrichment event that has an active span, so a service that enables the feature but never
 * evaluates a flag on a traced request still pays nothing.
 *
 * <p>All work is wrapped in try/catch — enrichment must NEVER break flag evaluation.
 */
public final class SpanEnrichmentWriter implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(SpanEnrichmentWriter.class);

  public static SpanEnrichmentWriter getInstance() {
    return SingletonHolder.INSTANCE;
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
      () -> resolveLocalRoot(AgentTracer.activeSpan());

  // Visible for tests: the local-root resolution, decoupled from the static AgentTracer so it can
  // be exercised without a live tracer.
  static AgentSpan resolveLocalRoot(final AgentSpan active) {
    if (active == null) {
      return null;
    }
    final AgentSpan localRoot = active.getLocalRootSpan();
    return localRoot != null ? localRoot : active;
  }

  private static final InterceptorRegistrar DEFAULT_REGISTRAR =
      interceptor -> GlobalTracer.get().addTraceInterceptor(interceptor);

  private static final class SingletonHolder {
    // Persisting this instance across FeatureFlaggingSystem start/stop keeps the single registered
    // interceptor (and its state) alive, so a restart never re-registers.
    private static final SpanEnrichmentWriter INSTANCE = new SpanEnrichmentWriter();
  }

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

  /**
   * Drops any residual state. The interceptor stays registered with the tracer (it cannot be
   * removed) but goes inert while the state is empty; later evaluations resume enrichment on the
   * same interceptor.
   */
  @Override
  public void close() {
    states.clear();
  }

  /**
   * Records an evaluation that resolved to a split with a serial id.
   *
   * @param serialId the split serial id.
   * @param doLog whether the allocation logs exposures, to also record the subject.
   * @param targetingKey the optional targeting key of the subject.
   */
  public void serialId(final int serialId, final boolean doLog, final String targetingKey) {
    try {
      final SpanEnrichmentAccumulator state = activeRootState();
      if (state != null) {
        state.addSerialId(serialId);
        if (doLog && targetingKey != null) {
          state.addSubject(targetingKey, serialId);
        }
      }
    } catch (final Throwable t) {
      // Never let span enrichment break flag evaluation; a debug line aids diagnosis if it does.
      log.debug("Span-enrichment accumulation failed", t);
    }
  }

  /**
   * Records an evaluation that resolved to its runtime default value.
   *
   * @param flagKey the flag key.
   * @param defaultValue the native default value.
   */
  public void runtimeDefault(final String flagKey, final Object defaultValue) {
    if (flagKey == null) {
      return;
    }
    try {
      final SpanEnrichmentAccumulator state = activeRootState();
      if (state != null) {
        state.addDefault(flagKey, defaultValue);
      }
    } catch (final Throwable t) {
      log.debug("Span-enrichment accumulation failed", t);
    }
  }

  private SpanEnrichmentAccumulator activeRootState() {
    final AgentSpan root = rootSpanResolver.activeLocalRoot();
    if (root == null) {
      return null; // no active span → nothing to enrich (and nothing to register the interceptor
      // for)
    }
    if (!ensureInterceptorRegistered()) {
      // The interceptor isn't registered (e.g. tracer absent), so nothing would ever flush this
      // state — skip accumulating. A later evaluation retries registration.
      return null;
    }
    return states.getOrCreate(root);
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

  RootSpanResolver rootSpanResolver() {
    return rootSpanResolver;
  }

  InterceptorRegistrar registrar() {
    return registrar;
  }
}

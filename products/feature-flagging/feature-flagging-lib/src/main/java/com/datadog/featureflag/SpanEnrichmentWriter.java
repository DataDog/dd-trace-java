package com.datadog.featureflag;

import datadog.trace.api.GlobalTracer;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.SpanEnrichmentEvent;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent-side owner of APM feature-flag span enrichment. This is the WRITE tier of the
 * capture-vs-write split: it listens on {@link FeatureFlaggingGateway} for {@link
 * SpanEnrichmentEvent}s dispatched by the published {@code dd-openfeature} provider during flag
 * evaluation, resolves the active local-root span, and accumulates per-trace state that a {@link
 * SpanEnrichmentInterceptor} later flushes onto the root when the trace completes.
 *
 * <p>Living in the agent classloader (rather than the application-loaded provider) means the tracer
 * API ({@link AgentSpan}/{@link AgentTracer}/{@link GlobalTracer}/{@code TraceInterceptor}) is used
 * only here — the published product API never depends on {@code internal-api}. It also gives span
 * enrichment a single, stable owner: no per-provider rebinding, no reconfiguration hazard, and no
 * application-classloader pinning.
 *
 * <p><b>Zero idle overhead when off.</b> When the span-enrichment gate is off the provider adds no
 * capture hook, so no seam events are dispatched, this listener never runs, and the interceptor is
 * never registered — the tracer's write path is untouched. The interceptor is registered lazily on
 * the first enrichment event, so a service that enables the feature but never evaluates a flag on a
 * traced request still pays nothing.
 *
 * <p>All work is wrapped in try/catch — enrichment must NEVER break flag evaluation.
 */
public final class SpanEnrichmentWriter implements FeatureFlaggingGateway.SpanEnrichmentListener {

  /**
   * Resolves the local-root span for the active trace. Injectable so tests need no static mocks.
   */
  interface RootSpanResolver {
    AgentSpan activeLocalRoot();
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

  private final SpanEnrichmentStates states = new SpanEnrichmentStates();
  private final SpanEnrichmentInterceptor interceptor = new SpanEnrichmentInterceptor(states);
  private final RootSpanResolver rootSpanResolver;
  // Registered with the tracer at most once, lazily on the first enrichment event.
  private final AtomicBoolean interceptorRegistered = new AtomicBoolean(false);

  public SpanEnrichmentWriter() {
    this(DEFAULT_RESOLVER);
  }

  SpanEnrichmentWriter(final RootSpanResolver rootSpanResolver) {
    this.rootSpanResolver = rootSpanResolver;
  }

  /** Starts listening for enrichment events. */
  public void init() {
    FeatureFlaggingGateway.addSpanEnrichmentListener(this);
  }

  /** Stops listening and drops any residual state. */
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
      ensureInterceptorRegistered();
      final AgentSpan root = rootSpanResolver.activeLocalRoot();
      if (root == null) {
        return; // no active span → nothing to enrich
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
      // Never let span enrichment break flag evaluation.
    }
  }

  private void ensureInterceptorRegistered() {
    if (interceptorRegistered.compareAndSet(false, true)) {
      try {
        GlobalTracer.get().addTraceInterceptor(interceptor);
      } catch (final Throwable t) {
        // Tracer not yet installed (e.g. no-op placeholder): allow a later event to retry.
        interceptorRegistered.set(false);
      }
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

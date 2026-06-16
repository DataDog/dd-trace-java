package datadog.trace.api.openfeature;

import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collection;
import java.util.Map;

/**
 * {@link TraceInterceptor} that writes the accumulated {@code ffe_*} tags onto the local root span
 * when a trace completes (JAVA-01). This is the WRITE half of the capture-vs-write split — the
 * CAPTURE half is {@link SpanEnrichmentHook}, which fills {@link SpanEnrichmentAccumulator#STATES}
 * during flag evaluation.
 *
 * <p>{@code onTraceComplete} finds the local root span in the collection (single pass — the root is
 * reachable via {@link MutableSpan#getLocalRootSpan()}), reads the accumulator keyed by that root's
 * trace id, writes the {@code ffe_*} tags, and removes the state. Span order is not guaranteed, so
 * the root is resolved through {@code getLocalRootSpan()} rather than by position.
 *
 * <p>The state is cleared on flush regardless of whether it had data, so a trace that captured
 * nothing (or whose accumulator was never created) leaves no residue (DG-005). All work is wrapped
 * in try/catch — enrichment must NEVER break trace finish (Pattern D).
 */
final class SpanEnrichmentInterceptor implements TraceInterceptor {

  /**
   * Unique priority in the "trace data enrichment" band, after {@code GIT_METADATA} (3) and before
   * the custom-sampling band ({@code Integer.MAX_VALUE - 2}). Distinct from every value in {@code
   * AbstractTraceInterceptor.Priority} and from the CI Visibility interceptors.
   */
  static final int PRIORITY = 4;

  // The tracer holds interceptors in an add-only sorted set keyed by priority with no public
  // removal API. On provider shutdown we cannot un-register, so we disable the singleton instead:
  // a disabled interceptor no-ops and drains any residual state (Pitfall 3 — provider-close
  // cleanup; idempotent because re-registration with the same priority is rejected).
  private volatile boolean enabled = true;

  /** Disables the interceptor and clears all accumulated state (provider-close cleanup). */
  void disable() {
    enabled = false;
    SpanEnrichmentAccumulator.STATES.clear();
  }

  boolean isEnabled() {
    return enabled;
  }

  @Override
  public Collection<? extends MutableSpan> onTraceComplete(
      final Collection<? extends MutableSpan> trace) {
    try {
      if (!enabled || trace == null || trace.isEmpty()) {
        return trace;
      }
      final MutableSpan localRoot = findLocalRoot(trace);
      if (!(localRoot instanceof AgentSpan)) {
        return trace; // cannot resolve trace id without AgentSpan; nothing to flush
      }
      final long traceKey = ((AgentSpan) localRoot).getTraceId().toLong();
      final SpanEnrichmentAccumulator state = SpanEnrichmentAccumulator.STATES.remove(traceKey);
      if (state == null || !state.hasData()) {
        return trace;
      }
      for (final Map.Entry<String, String> tag : state.toSpanTags().entrySet()) {
        final String value = tag.getValue();
        if (value != null && !value.isEmpty()) {
          localRoot.setTag(tag.getKey(), value);
        }
      }
    } catch (final Throwable t) {
      // Never let span enrichment break trace finish.
    }
    return trace;
  }

  private static MutableSpan findLocalRoot(final Collection<? extends MutableSpan> trace) {
    final MutableSpan first = trace.iterator().next();
    final MutableSpan localRoot = first.getLocalRootSpan();
    if (localRoot != null) {
      return localRoot;
    }
    // Fallback: scan for a span that is its own local root (no parent in this fragment).
    for (final MutableSpan span : trace) {
      if (span.getLocalRootSpan() == span || span.getLocalRootSpan() == null) {
        return span;
      }
    }
    return first;
  }

  @Override
  public int priority() {
    return PRIORITY;
  }
}

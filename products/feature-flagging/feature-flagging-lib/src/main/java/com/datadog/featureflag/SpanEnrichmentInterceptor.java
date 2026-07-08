package com.datadog.featureflag;

import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collection;
import java.util.Map;

/**
 * {@link TraceInterceptor} that writes the accumulated {@code ffe_*} tags onto the local root span
 * when a trace <em>actually completes</em>. This is the WRITE half of the capture-vs-write split —
 * the CAPTURE half is the agent-side {@link SpanEnrichmentWriter}, which fills the {@link
 * SpanEnrichmentStates} store from flag-evaluation seam events.
 *
 * <h2>Single agent-side owner</h2>
 *
 * <p>Exactly one interceptor is created and owned by the {@link SpanEnrichmentWriter} (agent
 * classloader) and registered with the tracer at most once, lazily on the first enrichment event.
 * Because the owner lives in the stable agent classloader — not per-provider in an application
 * classloader — there is no rebinding, no reconfiguration hazard, and no application-classloader
 * pinning.
 *
 * <h2>Partial-flush correctness</h2>
 *
 * <p>{@code dd-trace-core} runs {@code onTraceComplete} on <b>every</b> flush, not only on final
 * trace completion: {@code CoreTracer.write(SpanList)} → {@code interceptCompleteTrace(...)} fires
 * for both partial flushes ({@code PendingTrace.partialFlush()} → {@code write(true)}) and the
 * final write ({@code write(false)}). A <b>partial</b> flush deliberately <b>excludes the
 * still-open local root</b> — {@code PendingTrace} holds the root back ({@code rootSpanWritten})
 * and {@code getRootSpan()} returns null on a partial fragment, so {@code CoreTracer.write} does
 * not invoke {@code onRootSpanFinished} for it.
 *
 * <p>Therefore this interceptor flushes+removes state <b>only when the local root span is present
 * in the flushed collection</b> (i.e. this is the final write for the trace). On a partial flush
 * the root is absent, so we return early and <b>keep the accumulator intact</b>, preserving every
 * flag evaluated before the flush boundary. Without this guard, the first partial flush would drain
 * the accumulator and write tags onto a not-yet-finished root, silently dropping all pre-flush
 * enrichment — exactly the long-running-trace data-loss bug.
 *
 * <p>The store is weak-keyed by the local-root span, so a trace that never reaches this interceptor
 * is collected with its root and cannot leak unboundedly.
 *
 * <p>All work is wrapped in try/catch — enrichment must NEVER break trace finish.
 */
final class SpanEnrichmentInterceptor implements TraceInterceptor {

  /**
   * Unique priority in the "trace data enrichment" band, after {@code GIT_METADATA} (3) and before
   * the custom-sampling band ({@code Integer.MAX_VALUE - 2}). Distinct from every value in {@code
   * AbstractTraceInterceptor.Priority} and from the CI Visibility interceptors.
   */
  static final int PRIORITY = 4;

  private final SpanEnrichmentStates states;

  SpanEnrichmentInterceptor(final SpanEnrichmentStates states) {
    this.states = states;
  }

  @Override
  public Collection<? extends MutableSpan> onTraceComplete(
      final Collection<? extends MutableSpan> trace) {
    try {
      // Fast path: no accumulated state at all → skip the per-flush scan + lock entirely. This is
      // the common case for services that never evaluate a flag on a given trace.
      if (trace == null || trace.isEmpty() || states.isEmpty()) {
        return trace;
      }
      // Resolve the local root for this fragment, then require that the root is actually PRESENT in
      // this collection. A partial flush excludes the still-open root, so its absence means "not
      // the final write" — keep the accumulator and bail.
      final MutableSpan localRoot = findLocalRootInFragment(trace);
      if (!(localRoot instanceof AgentSpan)) {
        return trace; // partial flush, or no resolvable in-fragment root: keep state untouched
      }
      // Key by the local-root span object to match the capture-side keying.
      final SpanEnrichmentAccumulator state = states.remove((AgentSpan) localRoot);
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

  /**
   * Resolves the local root span for this fragment and returns it ONLY if it is actually present in
   * the fragment by reference identity. Returns {@code null} when the root is not in the collection
   * (a partial flush excludes the still-open root) or when no root can be safely identified.
   *
   * <p>We never guess: a non-root span is never returned. If the first span reports a non-null
   * local root, we accept it only after confirming that exact object is in the fragment; otherwise
   * we look for a span that is provably its own local root and present.
   */
  private static MutableSpan findLocalRootInFragment(
      final Collection<? extends MutableSpan> trace) {
    final MutableSpan first = trace.iterator().next();
    final MutableSpan candidate = first.getLocalRootSpan();
    if (candidate != null) {
      // Accept the reported local root only if it is genuinely part of THIS fragment. On a partial
      // flush the root is reachable by reference but NOT in the collection → reject (keep state).
      for (final MutableSpan span : trace) {
        if (span == candidate) {
          return candidate;
        }
      }
      return null; // root excluded from this fragment → partial flush, do not flush/remove
    }
    // Local root unknown for the first span: only accept a span that is provably its own local root
    // and present here. Never fall back to an arbitrary span.
    for (final MutableSpan span : trace) {
      if (span.getLocalRootSpan() == span) {
        return span;
      }
    }
    return null;
  }

  @Override
  public int priority() {
    return PRIORITY;
  }
}

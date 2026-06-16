package datadog.trace.api.openfeature;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collection;
import java.util.Map;

/**
 * {@link TraceInterceptor} that writes the accumulated {@code ffe_*} tags onto the local root span
 * when a trace <em>actually completes</em> (JAVA-01). This is the WRITE half of the
 * capture-vs-write split — the CAPTURE half is {@link SpanEnrichmentHook}, which fills this
 * interceptor's per-provider {@link SpanEnrichmentStates} store during flag evaluation.
 *
 * <h2>Partial-flush correctness (CR-01)</h2>
 *
 * <p>{@code dd-trace-core} runs {@code onTraceComplete} on <b>every</b> flush, not only on final
 * trace completion: {@code CoreTracer.write(SpanList)} →{@code interceptCompleteTrace(...)} fires
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
 * <h2>State ownership (CR-02 / CR-03)</h2>
 *
 * <p>State lives in a per-interceptor (per-provider) {@link SpanEnrichmentStates} store, not a
 * global static. {@link #disable()} clears only this instance's store, so one provider's shutdown
 * can never wipe another's in-flight state (CR-03). The store is hard-bounded, so a trace that
 * never reaches this interceptor cannot leak unboundedly (CR-02).
 *
 * <p>All work is wrapped in try/catch — enrichment must NEVER break trace finish (Pattern D).
 */
final class SpanEnrichmentInterceptor implements TraceInterceptor {

  /**
   * Unique priority in the "trace data enrichment" band, after {@code GIT_METADATA} (3) and before
   * the custom-sampling band ({@code Integer.MAX_VALUE - 2}). Distinct from every value in {@code
   * AbstractTraceInterceptor.Priority} and from the CI Visibility interceptors.
   */
  static final int PRIORITY = 4;

  // The tracer holds interceptors in an add-only sorted set keyed by priority with no public
  // removal API. On provider shutdown we cannot un-register, so we disable this instance instead:
  // a disabled interceptor no-ops and drains its own residual state (Pitfall 3 — provider-close
  // cleanup). Because state is instance-owned, disabling never touches another provider's state.
  private volatile boolean enabled = true;

  // Per-provider state store, shared with this provider's hook. Owned here so cleanup is scoped.
  private final SpanEnrichmentStates states;

  SpanEnrichmentInterceptor(final SpanEnrichmentStates states) {
    this.states = states;
  }

  /** The state store shared with this interceptor's capture hook. */
  SpanEnrichmentStates states() {
    return states;
  }

  /** Disables the interceptor and clears its OWN accumulated state (provider-close cleanup). */
  void disable() {
    enabled = false;
    states.clear();
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
      // Resolve the local root for this fragment, then require that the root is actually PRESENT in
      // this collection. A partial flush excludes the still-open root (CR-01), so its absence means
      // "not the final write" — keep the accumulator and bail.
      final MutableSpan localRoot = findLocalRootInFragment(trace);
      if (!(localRoot instanceof AgentSpan)) {
        return trace; // partial flush, or no resolvable in-fragment root: keep state untouched
      }
      final DDTraceId traceId = ((AgentSpan) localRoot).getTraceId();
      if (traceId == null) {
        return trace; // no trace id (e.g. Noop span) — cannot key state; keep it (WR-01)
      }
      final long traceKey = traceId.toLong();
      final SpanEnrichmentAccumulator state = states.remove(traceKey);
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
   * (a partial flush excludes the still-open root — CR-01) or when no root can be safely
   * identified.
   *
   * <p>We never guess: a non-root span is never returned (WR-02). If the first span reports a
   * non-null local root, we accept it only after confirming that exact object is in the fragment;
   * otherwise we look for a span that is provably its own local root and present.
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
    // and present here. Never fall back to an arbitrary span (WR-02).
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

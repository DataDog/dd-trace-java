package datadog.smoketest.trace;

import static java.util.function.UnaryOperator.identity;

import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedTrace;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Entry point for the thin smoke trace DSL. Assert a captured trace collection against one {@link
 * TraceMatcher} per expected trace:
 *
 * <pre>{@code
 * import static datadog.smoketest.trace.SmokeTraceAssertions.assertTraces;
 * import static datadog.smoketest.trace.TraceMatcher.trace;
 * import static datadog.smoketest.trace.SpanMatcher.span;
 *
 * assertTraces(traces, trace(span().operationName("servlet.request").resourceName("GET /greeting")));
 * }</pre>
 *
 * <p>A single matching algorithm is tuned by three orthogonal, combinable {@link Options}:
 *
 * <ul>
 *   <li>{@link #SORT_BY_START_TIME} / {@link #SORT_BY_ROOT_SPAN_ID} (the {@code sorter}) — sort the
 *       received traces before matching.
 *   <li>{@link #UNORDERED} — a matcher may match <em>any</em> distinct (unconsumed) trace rather
 *       than the one at its position. Without a sorter this is fully order-independent;
 *       <em>with</em> a sorter it is forward-only (once a matcher takes the trace at sorted
 *       position <i>p</i>, the next matcher can only look at positions after <i>p</i>) — i.e. match
 *       as a subsequence in sorted order.
 *   <li>{@link #IGNORE_ADDITIONAL_TRACES} — don't require every received trace to be matched; extra
 *       traces are ignored (a subset assertion). Without it, the counts must be equal.
 * </ul>
 *
 * <p>The default (no options) is thus count-exact positional matching (matcher <i>i</i> ↔ trace
 * <i>i</i>, received order). Combine flags with the fluent {@link Options} — e.g. {@code o ->
 * o.unordered().ignoreAdditionalTraces()} — to assert only the traces you care about in a
 * collection that also holds unrelated or non-deterministically-ordered ones (e.g. a distributed
 * test where connection-setup commands land as their own traces). The traces come from a {@code
 * TraceBackend} (mock or test-agent), both decoded to {@link DecodedTrace}.
 */
public final class SmokeTraceAssertions {
  /** Sorts traces by the earliest span start time. */
  public static final Comparator<List<DecodedSpan>> TRACE_START_TIME_COMPARATOR =
      Comparator.comparingLong(SmokeTraceAssertions::earliestStart);

  /** Sorts traces by their root span id (the span with no parent). */
  public static final Comparator<List<DecodedSpan>> TRACE_ROOT_SPAN_ID_COMPARATOR =
      Comparator.comparingLong(SmokeTraceAssertions::rootSpanId);

  /** Don't require every received trace to be matched — ignore the extras (a subset assertion). */
  public static final UnaryOperator<Options> IGNORE_ADDITIONAL_TRACES =
      Options::ignoreAdditionalTraces;

  /** Let each matcher match any distinct trace rather than the one at its position. */
  public static final UnaryOperator<Options> UNORDERED = Options::unordered;

  /** Sorts traces by earliest span start time before matching. */
  public static final UnaryOperator<Options> SORT_BY_START_TIME =
      options -> options.sorter(TRACE_START_TIME_COMPARATOR);

  /** Sorts traces by root span id before matching. */
  public static final UnaryOperator<Options> SORT_BY_ROOT_SPAN_ID =
      options -> options.sorter(TRACE_ROOT_SPAN_ID_COMPARATOR);

  private SmokeTraceAssertions() {}

  /** Asserts the traces against the matchers, one matcher per expected trace, in received order. */
  public static void assertTraces(List<DecodedTrace> traces, TraceMatcher... matchers) {
    assertTraces(traces, identity(), matchers);
  }

  /**
   * As {@link #assertTraces(List, TraceMatcher...)} with the given options. One matching pass,
   * tuned by {@code sorter} / {@link Options#unordered() unordered} / {@link
   * Options#ignoreAdditionalTraces() ignoreAdditionalTraces} — see the class doc for the flag
   * semantics.
   */
  public static void assertTraces(
      List<DecodedTrace> traces, UnaryOperator<Options> options, TraceMatcher... matchers) {
    Options opts = options.apply(new Options());
    // Copy each trace's spans to a mutable list so the matcher can sort/inspect them.
    List<List<DecodedSpan>> pool = new ArrayList<>(traces.size());
    for (DecodedTrace trace : traces) {
      pool.add(new ArrayList<>(trace.getSpans()));
    }
    if (opts.sorter != null) {
      pool.sort(opts.sorter);
    }
    // Walk the matchers, each consuming a distinct trace. `floor` is the lowest pool index a match
    // may use: it advances past each match to enforce order, except in fully-unordered mode (no
    // sorter) where it stays 0 so any remaining trace is a candidate. `consumed` keeps matches
    // distinct when the floor doesn't advance.
    boolean[] consumed = new boolean[pool.size()];
    int matched = 0;
    int floor = 0;
    for (int i = 0; i < matchers.length; i++) {
      int idx = findMatch(pool, consumed, floor, opts, matchers[i], i);
      consumed[idx] = true;
      matched++;
      if (!opts.unordered || opts.sorter != null) {
        floor = idx + 1;
      }
    }
    if (!opts.ignoreAdditionalTraces && matched != pool.size()) {
      throw new AssertionError(
          "Expected " + matchers.length + " traces but got " + pool.size() + ": " + pool);
    }
  }

  // Finds the trace this matcher consumes, or throws. Scans from `floor`, skipping consumed traces
  // and (unless matching strictly positionally) non-matching ones. A strictly-positional matcher
  // that fails is re-run with the throwing assertion so the failure names the offending span.
  private static int findMatch(
      List<List<DecodedSpan>> pool,
      boolean[] consumed,
      int floor,
      Options opts,
      TraceMatcher matcher,
      int i) {
    boolean strictPositional = !opts.unordered && !opts.ignoreAdditionalTraces;
    for (int j = floor; j < pool.size(); j++) {
      if (consumed[j]) {
        continue;
      }
      if (matcher.matches(pool.get(j), i)) {
        return j;
      }
      if (strictPositional) {
        break; // the trace at this position must match; no skipping ahead
      }
    }
    if (strictPositional && floor < pool.size() && !consumed[floor]) {
      matcher.assertTrace(pool.get(floor), i); // throws with the per-span reason
    }
    throw new AssertionError(
        "No received trace matched trace matcher #"
            + i
            + " among "
            + pool.size()
            + " received trace(s): "
            + pool);
  }

  private static long earliestStart(List<DecodedSpan> spans) {
    long earliest = Long.MAX_VALUE;
    for (DecodedSpan span : spans) {
      earliest = Math.min(earliest, span.getStart());
    }
    return spans.isEmpty() ? 0L : earliest;
  }

  private static long rootSpanId(List<DecodedSpan> spans) {
    for (DecodedSpan span : spans) {
      if (span.getParentId() == 0L) {
        return span.getSpanId();
      }
    }
    return spans.isEmpty() ? 0L : spans.get(0).getSpanId();
  }

  /** Trace-collection matching options; see the class doc for how they combine. */
  public static final class Options {
    boolean ignoreAdditionalTraces = false;
    boolean unordered = false;
    Comparator<List<DecodedSpan>> sorter = null; // null => keep received order

    /** Ignore received traces beyond those the matchers consume (a subset assertion). */
    public Options ignoreAdditionalTraces() {
      this.ignoreAdditionalTraces = true;
      return this;
    }

    /** Let each matcher match any distinct trace (forward-only when a {@link #sorter} is set). */
    public Options unordered() {
      this.unordered = true;
      return this;
    }

    public Options sorter(Comparator<List<DecodedSpan>> sorter) {
      this.sorter = sorter;
      return this;
    }
  }
}

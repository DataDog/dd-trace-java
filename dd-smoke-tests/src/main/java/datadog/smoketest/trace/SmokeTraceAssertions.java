package datadog.smoketest.trace;

import static java.lang.Long.MAX_VALUE;
import static java.lang.Math.min;
import static java.util.function.UnaryOperator.identity;
import static org.junit.jupiter.api.AssertionFailureBuilder.assertionFailure;

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
  public static final Comparator<DecodedTrace> TRACE_START_TIME_COMPARATOR =
      Comparator.comparingLong(SmokeTraceAssertions::earliestStart);

  /** Don't require every received trace to be matched — ignore the extras (a subset assertion). */
  public static final UnaryOperator<Options> IGNORE_ADDITIONAL_TRACES =
      Options::ignoreAdditionalTraces;

  /** Let each matcher match any distinct trace rather than the one at its position. */
  public static final UnaryOperator<Options> UNORDERED = Options::unordered;

  /** Sorts traces by earliest span start time before matching. */
  public static final UnaryOperator<Options> SORT_BY_START_TIME =
      options -> options.sorter(TRACE_START_TIME_COMPARATOR);

  private SmokeTraceAssertions() {}

  /**
   * Checks the structure of a trace collection.
   *
   * @param traces The trace collection to check.
   * @param matchers The matchers to verify the trace collection, one matcher by expected trace.
   */
  public static void assertTraces(List<DecodedTrace> traces, TraceMatcher... matchers) {
    assertTraces(traces, identity(), matchers);
  }

  /**
   * Checks the structure of a trace collection.
   *
   * @param traces The trace collection to check.
   * @param options The {@link Options} to configure the checks.
   * @param matchers The matchers to verify the trace collection, one matcher by expected trace.
   */
  public static void assertTraces(
      List<DecodedTrace> traces, UnaryOperator<Options> options, TraceMatcher... matchers) {
    Options opts = options.apply(new Options());
    // Check trace count first
    int traceCount = traces.size();
    if (opts.ignoreAdditionalTraces) {
      if (traceCount < matchers.length) {
        assertionFailure()
            .message("Not enough of traces")
            .expected(matchers.length)
            .actual(traceCount)
            .buildAndThrow();
      }
    } else {
      if (traceCount != matchers.length) {
        assertionFailure()
            .message("Invalid number of traces")
            .expected(matchers.length)
            .actual(traceCount)
            .buildAndThrow();
      }
    }
    // Apply sorter
    if (opts.sorter != null) {
      traces = new ArrayList<>(traces);
      traces.sort(opts.sorter);
    }
    boolean strictPositional = !opts.unordered && !opts.ignoreAdditionalTraces;
    // Record matched traces for unordered mode
    boolean[] traceMatched = new boolean[traceCount];
    // Record last matching trace index for ignoreAdditionalTraces mode
    int floor = 0;

    for (int matcherIndex = 0; matcherIndex < matchers.length; matcherIndex++) {
      TraceMatcher matcher = matchers[matcherIndex];
      if (strictPositional) {
        matcher.assertTrace(traces.get(matcherIndex).getSpans(), matcherIndex);
        continue;
      }

      boolean matched = false;
      for (int traceIndex = floor; traceIndex < traceCount; traceIndex++) {
        // Skipped already matched traces - unordered mode only
        if (traceMatched[traceIndex]) {
          continue;
        }
        if (matcher.matches(traces.get(traceIndex).getSpans(), matcherIndex)) {
          matched = true;
          if (opts.unordered) {
            traceMatched[traceIndex] = true;
          } else {
            floor = traceIndex + 1;
          }
          break;
        }
      }
      if (!matched) {
        assertionFailure().message("No trace matches matcher # "+ matcherIndex).buildAndThrow();
      }
    }
  }

  private static long earliestStart(DecodedTrace trace) {
    long start = MAX_VALUE;
    for (DecodedSpan span : trace.getSpans()) {
      start = min(start, span.getStart());
    }
    return start == MAX_VALUE ? 0L : start;
  }

  /** Trace-collection matching options; see the class doc for how they combine. */
  public static final class Options {
    boolean ignoreAdditionalTraces = false;
    boolean unordered = false;
    Comparator<DecodedTrace> sorter = null; // null => keep received order

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

    public Options sorter(Comparator<DecodedTrace> sorter) {
      this.sorter = sorter;
      return this;
    }
  }
}

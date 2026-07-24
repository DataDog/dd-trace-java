package datadog.smoketest.trace;

import static java.lang.Long.MAX_VALUE;
import static java.lang.Math.min;
import static java.util.function.UnaryOperator.identity;
import static org.junit.jupiter.api.AssertionFailureBuilder.assertionFailure;

import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedTrace;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * This class is a helper class to verify trace structure.
 *
 * <p>To check for trace structure, use the static factory methods: {@link #assertTraces(List,
 * TraceMatcher...)} with the expected {@link TraceMatcher}s (one per trace), or {@link
 * #assertTraces(List, UnaryOperator, TraceMatcher...)} to configure the checks with a {@link
 * Options} object.
 *
 * <p>The following predefined configurations:
 *
 * <ul>
 *   <li>{@link #IGNORE_ADDITIONAL_TRACES} ignores additional traces if there are more than
 *       expected,
 *   <li>{@link #SORT_BY_START_TIME} sorts traces by their start time,
 *   <li>{@link #UNORDERED} allows matchers to match any distinct trace rather than the one at its
 *       position.
 * </ul>
 */
public final class SmokeTraceAssertions {
  /*
   * Trace comparators.
   */
  /** Trace comparator to sort by start time. */
  public static final Comparator<DecodedTrace> TRACE_START_TIME_COMPARATOR =
      Comparator.comparingLong(SmokeTraceAssertions::earliestStart);

  /*
   * Trace assertion options.
   */
  /** Ignores additional traces. If there are more traces than expected, do not fail. */
  public static final UnaryOperator<Options> IGNORE_ADDITIONAL_TRACES =
      Options::ignoreAdditionalTraces;

  /** Allows matchers to match any distinct trace rather than the one at its position. */
  public static final UnaryOperator<Options> UNORDERED = Options::unorder;

  /** Sorts traces by start time. */
  public static final UnaryOperator<Options> SORT_BY_START_TIME =
      options -> options.sort(TRACE_START_TIME_COMPARATOR);

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
    // Assert traces
    boolean strictPositional = !opts.unordered && !opts.ignoreAdditionalTraces;
    BitSet skippedTraces = new BitSet(traceCount);
    for (int matcherIndex = 0; matcherIndex < matchers.length; matcherIndex++) {
      TraceMatcher matcher = matchers[matcherIndex];
      if (strictPositional) {
        matcher.assertTrace(traces.get(matcherIndex).getSpans(), matcherIndex);
        continue;
      }
      boolean matched = false;
      for (int traceIndex = skippedTraces.nextClearBit(0); traceIndex < traceCount; traceIndex++) {
        if (skippedTraces.get(traceIndex)) {
          continue;
        }
        try {
          matcher.assertTrace(traces.get(traceIndex).getSpans(), matcherIndex);
          matched = true;
          if (opts.unordered) {
            skippedTraces.set(traceIndex);
          } else {
            skippedTraces.set(0, traceIndex);
          }
          break;
        } catch (AssertionError ignored) {
          // Swallow assertion errors, keep looking for a match
        }
      }
      if (!matched) {
        assertionFailure().message("No trace matches matcher # " + matcherIndex).buildAndThrow();
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

  public static final class Options {
    boolean ignoreAdditionalTraces = false;
    boolean unordered = false;
    Comparator<DecodedTrace> sorter = null;

    public Options ignoreAdditionalTraces() {
      this.ignoreAdditionalTraces = true;
      return this;
    }

    public Options unorder() {
      this.unordered = true;
      this.sorter = null;
      return this;
    }

    public Options sort(Comparator<DecodedTrace> sorter) {
      this.unordered = false;
      this.sorter = sorter;
      return this;
    }
  }
}

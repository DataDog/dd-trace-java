package datadog.trace.agent.test.assertions;

import static java.util.function.Function.identity;

import datadog.trace.core.DDSpan;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import org.opentest4j.AssertionFailedError;

public final class TraceAssertions {
  /** Trace comparator to sort by start time. */
  public static final Comparator<List<DDSpan>> TRACE_START_TIME_COMPARATOR =
      Comparator.comparingLong(
          trace -> trace.isEmpty() ? 0L : trace.get(0).getLocalRootSpan().getStartTime());

  /** Trace comparator to sort by root span identifier. */
  public static final Comparator<List<DDSpan>> TRACE_ROOT_SPAN_ID_COMPARATOR =
      Comparator.comparingLong(
          trace -> trace.isEmpty() ? 0L : trace.get(0).getLocalRootSpan().getSpanId());

  /*
   * Trace assertions options.
   */
  /** Ignores addition traces. If there are more traces than expected, do not fail. */
  public static final Function<Options, Options> IGNORE_ADDITIONAL_TRACES =
      Options::ignoredAdditionalTraces;

  /** Sorts traces by start time. */
  public static final Function<Options, Options> SORT_BY_START_TIME =
      options -> options.sorter(TRACE_START_TIME_COMPARATOR);

  /** Sorts traces by their root span identifier. */
  public static final Function<Options, Options> SORT_BY_ROOT_SPAN_ID =
      options -> options.sorter(TRACE_ROOT_SPAN_ID_COMPARATOR);

  private TraceAssertions() {}

  /**
   * Checks a trace structure.
   *
   * @param trace The trace to check.
   * @param matcher The matcher to verify the trace structure.
   */
  public static void assertTrace(List<DDSpan> trace, TraceMatcher matcher) {
    matcher.assertTrace(trace, 0);
  }

  /**
   * Checks the structure of a trace collection.
   *
   * @param traces The trace collection to check.
   * @param matchers The matchers to verify the trace collection, one matcher by expected trace.
   */
  public static void assertTraces(List<List<DDSpan>> traces, TraceMatcher... matchers) {
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
      List<List<DDSpan>> traces, Function<Options, Options> options, TraceMatcher... matchers) {
    Options opts = options.apply(new Options());
    int expectedTraceCount = matchers.length;
    int traceCount = traces.size();
    if (opts.ignoredAdditionalTraces) {
      if (traceCount < expectedTraceCount) {
        throw new AssertionFailedError("Not enough of traces", expectedTraceCount, traceCount);
      }
    } else {
      if (traceCount != expectedTraceCount) {
        throw new AssertionFailedError("Invalid number of traces", expectedTraceCount, traceCount);
      }
    }
    if (opts.sorter != null) {
      traces.sort(opts.sorter);
    }
    for (int i = 0; i < expectedTraceCount; i++) {
      List<DDSpan> trace = traces.get(i);
      matchers[i].assertTrace(trace, i);
    }
  }

  public static class Options {
    boolean ignoredAdditionalTraces = false;
    Comparator<List<DDSpan>> sorter = TRACE_START_TIME_COMPARATOR;

    public Options ignoredAdditionalTraces() {
      this.ignoredAdditionalTraces = true;
      return this;
    }

    public Options sorter(Comparator<List<DDSpan>> sorter) {
      this.sorter = sorter;
      return this;
    }
  }
}

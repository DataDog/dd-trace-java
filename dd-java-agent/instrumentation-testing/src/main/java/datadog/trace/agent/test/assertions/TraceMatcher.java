package datadog.trace.agent.test.assertions;

import datadog.trace.core.DDSpan;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import org.opentest4j.AssertionFailedError;

/**
 * This class is a helper class to verify a trace structure.
 *
 * <p>To get a {@code TraceMatcher}, use the static factory methods: {@link #trace(SpanMatcher...)}
 * with the expected {@link SpanMatcher}s (one per expected span), or {@link #trace(Function,
 * SpanMatcher...)} to configure the checks with a {@link Options} object.
 *
 * <p>{@link #SORT_BY_START_TIME} can be used as predefined configuration to sort spans by start
 * time.
 *
 * @see TraceAssertions
 * @see SpanMatcher
 */
public final class TraceMatcher {
  public static final Comparator<DDSpan> START_TIME_COMPARATOR =
      Comparator.comparingLong(DDSpan::getStartTime);
  public static Function<Options, Options> SORT_BY_START_TIME =
      options -> options.sorter(START_TIME_COMPARATOR);

  private final Options options;
  private final SpanMatcher[] matchers;

  private TraceMatcher(Options options, SpanMatcher[] matchers) {
    if (matchers.length == 0) {
      throw new IllegalArgumentException("No span matchers provided");
    }
    this.options = options;
    this.matchers = matchers;
  }

  /**
   * Checks a trace structure.
   *
   * @param matchers The matchers to verify the trace structure.
   */
  public static TraceMatcher trace(SpanMatcher... matchers) {
    return new TraceMatcher(new Options(), matchers);
  }

  /**
   * Checks a trace structure.
   *
   * @param options The {@link TraceAssertions.Options} to configure the checks.
   * @param matchers The matchers to verify the trace structure.
   */
  public static TraceMatcher trace(Function<Options, Options> options, SpanMatcher... matchers) {
    return new TraceMatcher(options.apply(new Options()), matchers);
  }

  void assertTrace(List<DDSpan> trace, int traceIndex) {
    int spanCount = trace.size();
    if (spanCount != this.matchers.length) {
      throw new AssertionFailedError(
          "Invalid number of spans for trace " + traceIndex + " : " + trace,
          this.matchers.length,
          spanCount);
    }
    if (this.options.sorter != null) {
      trace.sort(this.options.sorter);
    }
    DDSpan previousSpan = null;
    for (int i = 0; i < spanCount; i++) {
      DDSpan span = trace.get(i);
      this.matchers[i].assertSpan(span, previousSpan);
      previousSpan = span;
    }
  }

  public static class Options {
    Comparator<DDSpan> sorter = null;

    public Options sorter(Comparator<DDSpan> sorter) {
      this.sorter = sorter;
      return this;
    }
  }
}

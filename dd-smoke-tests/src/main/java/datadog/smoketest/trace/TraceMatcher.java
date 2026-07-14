package datadog.smoketest.trace;

import static java.util.Comparator.comparingLong;

import datadog.trace.test.agent.decoder.DecodedSpan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Thin trace matcher for smoke tests: one {@link SpanMatcher} per expected span in a trace.
 * Companion of {@link SpanMatcher}; see its class doc for the serialized-model rationale.
 *
 * <p>Spans are matched positionally. By default they are matched in the order received; pass {@link
 * #SORT_BY_START_TIME} to {@link #trace(UnaryOperator, SpanMatcher...)} to sort them first, which
 * makes {@link SpanMatcher#childOfIndex(int)} / {@link SpanMatcher#childOfPrevious()}
 * deterministic.
 */
public final class TraceMatcher {
  /** Sorts a trace's spans by start time. */
  public static final Comparator<DecodedSpan> START_TIME_COMPARATOR =
      comparingLong(DecodedSpan::getStart);

  /** Configures {@link #trace(UnaryOperator, SpanMatcher...)} to sort spans by start time. */
  public static final UnaryOperator<Options> SORT_BY_START_TIME =
      options -> options.sorter(START_TIME_COMPARATOR);

  private final Options options;
  private final SpanMatcher[] spanMatchers;

  private TraceMatcher(Options options, SpanMatcher[] spanMatchers) {
    if (spanMatchers.length == 0) {
      throw new IllegalArgumentException("No span matchers provided");
    }
    this.options = options;
    this.spanMatchers = spanMatchers;
  }

  /** Checks a trace structure, one matcher per expected span, in the order received. */
  public static TraceMatcher trace(SpanMatcher... matchers) {
    return new TraceMatcher(new Options(), matchers);
  }

  /** Checks a trace structure with the given options (e.g. {@link #SORT_BY_START_TIME}). */
  public static TraceMatcher trace(UnaryOperator<Options> options, SpanMatcher... matchers) {
    return new TraceMatcher(options.apply(new Options()), matchers);
  }

  void assertTrace(List<DecodedSpan> spans, int traceIndex) {
    if (spans.size() != spanMatchers.length) {
      throw new AssertionError(
          "Expected "
              + spanMatchers.length
              + " spans in trace "
              + traceIndex
              + " but got "
              + spans.size()
              + ": "
              + spans);
    }
    List<DecodedSpan> ordered = new ArrayList<>(spans);
    if (options.sorter != null) {
      ordered.sort(options.sorter);
    }
    for (int i = 0; i < spanMatchers.length; i++) {
      spanMatchers[i].assertSpan(ordered, i);
    }
  }

  /** Per-trace matching options. */
  public static final class Options {
    Comparator<DecodedSpan> sorter = null; // null => keep received order

    public Options sorter(Comparator<DecodedSpan> sorter) {
      this.sorter = sorter;
      return this;
    }
  }
}

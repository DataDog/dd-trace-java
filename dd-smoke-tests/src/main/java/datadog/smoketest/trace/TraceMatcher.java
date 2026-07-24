package datadog.smoketest.trace;

import static java.util.Comparator.comparingLong;
import static java.util.stream.Collectors.toSet;

import datadog.trace.test.agent.decoder.DecodedSpan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.opentest4j.AssertionFailedError;

/**
 * This class is a helper class to verify a trace structure.
 *
 * <p>To get a {@link TraceMatcher}, use the static factory methods: {@link #trace(SpanMatcher...)}
 * with the expected {@link SpanMatcher}s (one per expected span), or {@link #trace(UnaryOperator,
 * SpanMatcher...)} to configure the checks with a {@link Options} object.
 *
 * <p>The following predefined configurations:
 *
 * <ul>
 *   <li>{@link #SORT_BY_START_TIME} sorts spans by start time,
 *   <li>{@link #SORT_BY_ANCESTRY} sorts spans by ancestry, root spans (or which parents are not
 *       present in the trace chunk) first, followed by their children by start time, depth-first
 * </ul>
 *
 * @see SmokeTraceAssertions
 * @see SpanMatcher
 */
public final class TraceMatcher {
  /*
   * Span comparators.
   */
  /** Span comparator to sort by start time. */
  public static final Comparator<DecodedSpan> START_TIME_COMPARATOR =
      comparingLong(DecodedSpan::getStart).thenComparingLong(DecodedSpan::getSpanId);

  /*
   * Span assertion options.
   */
  /** Sorts spans by start time. */
  public static final UnaryOperator<Options> SORT_BY_START_TIME =
      options -> options.sort(START_TIME_COMPARATOR);

  /**
   * Sorts spans by ancestry, root spans (or which parents are absent from the trace chunk) first,
   * followed by their children by start time, depth-first.
   */
  public static final UnaryOperator<Options> SORT_BY_ANCESTRY = Options::sortByAncestry;

  private final Options options;
  private final SpanMatcher[] matchers;

  private TraceMatcher(Options options, SpanMatcher[] spanMatchers) {
    if (spanMatchers.length == 0) {
      throw new IllegalArgumentException("No span matchers provided");
    }
    this.options = options;
    this.matchers = spanMatchers;
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
   * @param options The {@link TraceMatcher.Options} to configure the checks.
   * @param matchers The matchers to verify the trace structure.
   */
  public static TraceMatcher trace(UnaryOperator<Options> options, SpanMatcher... matchers) {
    return new TraceMatcher(options.apply(new Options()), matchers);
  }

  void assertTrace(List<DecodedSpan> spans, int traceIndex) {
    if (spans.size() != this.matchers.length) {
      throw new AssertionFailedError(
          "Invalid number of spans for trace " + traceIndex + " : " + spans,
          this.matchers.length,
          spans.size());
    }
    if (this.options.sortByAncestry) {
      spans = sortByAncestry(spans);
    } else if (this.options.comparator != null) {
      spans = new ArrayList<>(spans);
      spans.sort(this.options.comparator);
    }
    for (int i = 0; i < this.matchers.length; i++) {
      this.matchers[i].assertSpan(spans, i);
    }
  }

  private static List<DecodedSpan> sortByAncestry(List<DecodedSpan> spans) {
    Set<Long> spanIds = spans.stream().map(DecodedSpan::getSpanId).collect(toSet());
    Map<Long, List<DecodedSpan>> spansByParentId = new HashMap<>();
    for (DecodedSpan span : spans) {
      long parentId = span.getParentId();
      if (parentId != 0 && !spanIds.contains(parentId)) {
        parentId = 0;
      }
      spansByParentId.computeIfAbsent(parentId, k -> new ArrayList<>()).add(span);
    }
    spansByParentId.forEach((k, v) -> v.sort(START_TIME_COMPARATOR));

    List<DecodedSpan> ordered = new ArrayList<>(spans.size());
    appendChildren(ordered, spansByParentId.get(0L), spansByParentId);
    return ordered;
  }

  private static void appendChildren(
      List<DecodedSpan> orderedSpan,
      List<DecodedSpan> children,
      Map<Long, List<DecodedSpan>> spansByParentId) {
    for (DecodedSpan child : children) {
      orderedSpan.add(child);
      List<DecodedSpan> grandChildren = spansByParentId.get(child.getSpanId());
      if (grandChildren != null) {
        appendChildren(orderedSpan, grandChildren, spansByParentId);
      }
    }
  }

  public static final class Options {
    private Comparator<DecodedSpan> comparator = null;
    private boolean sortByAncestry = false;

    public Options sort(Comparator<DecodedSpan> comparator) {
      this.comparator = comparator;
      this.sortByAncestry = false;
      return this;
    }

    Options sortByAncestry() {
      this.comparator = null;
      this.sortByAncestry = true;
      return this;
    }
  }
}

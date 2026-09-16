package datadog.trace.agent.test.assertions;

import static java.util.Comparator.comparingLong;
import static java.util.stream.Collectors.toSet;

import datadog.trace.core.DDSpan;
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
 * <p>To get a {@code TraceMatcher}, use the static factory methods: {@link #trace(SpanMatcher...)}
 * with the expected {@link SpanMatcher}s (one per expected span), or {@link #trace(UnaryOperator,
 * SpanMatcher...)} to configure the checks with a {@link Options} object.
 *
 * <p>The following predefined configurations:
 *
 * <ul>
 *   <li>{@link #SORT_BY_START_TIME} sorts spans by start time,
 *   <li>{@link #SORT_BY_ANCESTRY} sorts spans by ancestry, root spans (or which parents are not
 *       present in the trace chunk) first, followed by their children by start time, depth-first *
 * </ul>
 *
 * @see TraceAssertions
 * @see SpanMatcher
 */
public final class TraceMatcher {
  /*
   * Span comparators.
   */
  /** Span comparator to sort by start time. */
  public static final Comparator<DDSpan> START_TIME_COMPARATOR =
      comparingLong(DDSpan::getStartTime).thenComparingLong(DDSpan::getSpanId);

  /*
   * Span assertion options.
   */
  /** Sorts spans by start time. */
  public static UnaryOperator<Options> SORT_BY_START_TIME =
      options -> options.sort(START_TIME_COMPARATOR);

  /**
   * Sorts spans by ancestry, root spans (or which parents are absent from the trace chunk) first,
   * followed by their children by start time, depth-first.
   */
  public static final UnaryOperator<Options> SORT_BY_ANCESTRY = Options::sortByAncestry;

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
  public static TraceMatcher trace(UnaryOperator<Options> options, SpanMatcher... matchers) {
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
    if (this.options.sortByAncestry) {
      trace = sortByAncestry(trace);
    } else if (this.options.comparator != null) {
      trace = new ArrayList<>(trace);
      trace.sort(this.options.comparator);
    }
    for (int spanIndex = 0; spanIndex < spanCount; spanIndex++) {
      this.matchers[spanIndex].assertSpan(trace, spanIndex);
    }
  }

  private static List<DDSpan> sortByAncestry(List<DDSpan> spans) {
    Set<Long> spanIds = spans.stream().map(DDSpan::getSpanId).collect(toSet());
    Map<Long, List<DDSpan>> spansByParentId = new HashMap<>();
    for (DDSpan span : spans) {
      long parentId = span.getParentId();
      if (parentId != 0 && !spanIds.contains(parentId)) {
        parentId = 0;
      }
      spansByParentId.computeIfAbsent(parentId, k -> new ArrayList<>()).add(span);
    }
    spansByParentId.forEach((k, v) -> v.sort(START_TIME_COMPARATOR));

    List<DDSpan> ordered = new ArrayList<>(spans.size());
    appendChildren(ordered, spansByParentId.get(0L), spansByParentId);
    return ordered;
  }

  private static void appendChildren(
      List<DDSpan> orderedSpan, List<DDSpan> children, Map<Long, List<DDSpan>> spansByParentId) {
    for (DDSpan child : children) {
      orderedSpan.add(child);
      List<DDSpan> grandChildren = spansByParentId.get(child.getSpanId());
      if (grandChildren != null) {
        appendChildren(orderedSpan, grandChildren, spansByParentId);
      }
    }
  }

  public static final class Options {
    private Comparator<DDSpan> comparator = null;
    private boolean sortByAncestry = false;

    public Options sort(Comparator<DDSpan> comparator) {
      this.comparator = comparator;
      this.sortByAncestry = false;
      return this;
    }

    private Options sortByAncestry() {
      this.comparator = null;
      this.sortByAncestry = true;
      return this;
    }
  }
}

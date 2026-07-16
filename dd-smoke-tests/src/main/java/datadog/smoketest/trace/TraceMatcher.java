package datadog.smoketest.trace;

import static java.util.Comparator.comparingLong;

import datadog.trace.test.agent.decoder.DecodedSpan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Thin trace matcher for smoke tests: one {@link SpanMatcher} per expected span in a trace.
 * Companion of {@link SpanMatcher}; see its class doc for the serialized-model rationale.
 *
 * <p>Spans are matched positionally. By default they are matched in the order received; sort them
 * first for a deterministic position:
 *
 * <ul>
 *   <li>{@link #SORT_BY_START_TIME} — by span start time. Beware: spans that start within the same
 *       clock tick (e.g. a publish and its broker-side deliver) can race, so this order is <em>not
 *       stable</em> for tightly-coupled concurrent spans.
 *   <li>{@link #SORT_BY_PARENT_CHAIN} — root→leaf following parent links. Stable regardless of
 *       timestamps, but only defined for a <em>single linear chain</em> (each span has at most one
 *       child); it throws otherwise. Ideal for a straight request→...→response distributed trace.
 * </ul>
 *
 * <p>A sorted order also makes {@link SpanMatcher#childOfIndex(int)} / {@link
 * SpanMatcher#childOfPrevious()} deterministic.
 */
public final class TraceMatcher {
  /** Sorts a trace's spans by start time. */
  public static final Comparator<DecodedSpan> START_TIME_COMPARATOR =
      comparingLong(DecodedSpan::getStart);

  /** Configures {@link #trace(UnaryOperator, SpanMatcher...)} to sort spans by start time. */
  public static final UnaryOperator<Options> SORT_BY_START_TIME =
      options -> options.sort(START_TIME_COMPARATOR);

  /**
   * Configures {@link #trace(UnaryOperator, SpanMatcher...)} to order spans root→leaf by following
   * parent links (see {@link Options#linearizeByParentChain()}). Timestamp-independent, so unlike
   * {@link #SORT_BY_START_TIME} it is stable for concurrent spans — but only valid for a single
   * linear chain.
   */
  public static final UnaryOperator<Options> SORT_BY_PARENT_CHAIN = Options::linearizeByParentChain;

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
    List<DecodedSpan> ordered;
    if (options.linearizeByParentChain) {
      ordered = chainOrder(spans);
    } else {
      ordered = new ArrayList<>(spans);
      if (options.comparator != null) {
        ordered.sort(options.comparator);
      }
    }
    for (int i = 0; i < spanMatchers.length; i++) {
      spanMatchers[i].assertSpan(ordered, i);
    }
  }

  /**
   * Whether this matcher accepts {@code spans} as a whole trace, without throwing — the boolean
   * counterpart of {@link #assertTrace} used by order-independent matching (see {@link
   * SmokeTraceAssertions#assertTraces} with {@link SmokeTraceAssertions#IGNORE_ADDITIONAL_TRACES}),
   * where each matcher must find some received trace it fits. A non-linear trace under {@link
   * #SORT_BY_PARENT_CHAIN} counts as "does not match" here (rather than propagating) so probing can
   * move on.
   */
  boolean matches(List<DecodedSpan> spans, int traceIndex) {
    try {
      assertTrace(spans, traceIndex);
      return true;
    } catch (AssertionError | IllegalStateException ignored) {
      return false;
    }
  }

  /**
   * Orders spans root→leaf by following parent links. Requires a single linear chain: exactly one
   * root (parent id 0) and every span at most one child, with all spans reachable from the root.
   * Throws {@link IllegalStateException} otherwise, so a non-chain trace fails loudly rather than
   * being silently mis-ordered.
   */
  private static List<DecodedSpan> chainOrder(List<DecodedSpan> spans) {
    DecodedSpan root = null;
    Map<Long, DecodedSpan> childByParent = new HashMap<>();
    for (DecodedSpan span : spans) {
      if (span.getParentId() == 0L) {
        if (root != null) {
          throw new IllegalStateException(
              "SORT_BY_PARENT_CHAIN requires a single root span (parent id 0); found more than one");
        }
        root = span;
      } else if (childByParent.put(span.getParentId(), span) != null) {
        throw new IllegalStateException(
            "SORT_BY_PARENT_CHAIN requires a linear chain, but span "
                + span.getParentId()
                + " has more than one child");
      }
    }
    if (root == null) {
      throw new IllegalStateException("SORT_BY_PARENT_CHAIN requires a root span (parent id 0)");
    }
    List<DecodedSpan> ordered = new ArrayList<>(spans.size());
    for (DecodedSpan current = root;
        current != null;
        current = childByParent.get(current.getSpanId())) {
      ordered.add(current);
    }
    if (ordered.size() != spans.size()) {
      throw new IllegalStateException(
          "SORT_BY_PARENT_CHAIN: trace is not a single chain ("
              + ordered.size()
              + " of "
              + spans.size()
              + " spans reachable from the root)");
    }
    return ordered;
  }

  public static final class Options {
    Comparator<DecodedSpan> comparator = null; // null => keep received order
    boolean linearizeByParentChain = false;

    public Options sort(Comparator<DecodedSpan> comparator) {
      this.comparator = comparator;
      return this;
    }

    /**
     * Order spans root→leaf by parent links instead of by a comparator (see {@link
     * #SORT_BY_PARENT_CHAIN}).
     */
    public Options linearizeByParentChain() {
      this.linearizeByParentChain = true;
      return this;
    }
  }
}

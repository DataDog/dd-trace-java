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
 * <p>Traces are matched positionally, in the order received by default. For order-independence pass
 * {@link #SORT_BY_START_TIME} / {@link #SORT_BY_ROOT_SPAN_ID}; to assert only a subset of the
 * received traces pass {@link #IGNORE_ADDITIONAL_TRACES}. The traces come from a {@code
 * TraceBackend} (mock or test-agent), both decoded to {@link DecodedTrace}.
 */
public final class SmokeTraceAssertions {
  /** Sorts traces by the earliest span start time. */
  public static final Comparator<List<DecodedSpan>> TRACE_START_TIME_COMPARATOR =
      Comparator.comparingLong(SmokeTraceAssertions::earliestStart);

  /** Sorts traces by their root span id (the span with no parent). */
  public static final Comparator<List<DecodedSpan>> TRACE_ROOT_SPAN_ID_COMPARATOR =
      Comparator.comparingLong(SmokeTraceAssertions::rootSpanId);

  /** Do not fail when there are more traces than matchers (assert a subset). */
  public static final UnaryOperator<Options> IGNORE_ADDITIONAL_TRACES =
      Options::ignoreAdditionalTraces;

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

  /** As {@link #assertTraces(List, TraceMatcher...)} with the given options. */
  public static void assertTraces(
      List<DecodedTrace> traces, UnaryOperator<Options> options, TraceMatcher... matchers) {
    Options opts = options.apply(new Options());
    int expected = matchers.length;
    int actual = traces.size();
    if (opts.ignoreAdditionalTraces) {
      if (actual < expected) {
        throw new AssertionError("Expected at least " + expected + " traces but got " + actual);
      }
    } else if (actual != expected) {
      throw new AssertionError("Expected " + expected + " traces but got " + actual);
    }
    // Copy each trace's spans to a mutable list so the matcher can sort/inspect them.
    List<List<DecodedSpan>> spanLists = new ArrayList<>(actual);
    for (DecodedTrace trace : traces) {
      spanLists.add(new ArrayList<>(trace.getSpans()));
    }
    if (opts.sorter != null) {
      spanLists.sort(opts.sorter);
    }
    for (int i = 0; i < expected; i++) {
      matchers[i].assertTrace(spanLists.get(i), i);
    }
  }

  /**
   * Asserts that some received trace contains a parent-child <em>chain</em> of spans matching
   * {@code chain} in order: {@code chain[0]} matches a span (a trace root if it declares {@link
   * SpanMatcher#root()}), and each {@code chain[i]} matches a direct child of {@code chain[i-1]}'s
   * span. Unlike {@link #assertTraces}, this is a <em>subset</em> match — extra spans in the trace
   * are ignored — which suits distributed traces whose full span set is large and timing-dependent
   * (only span linkage, not exact shape, is asserted). Field constraints are declared per span; the
   * chain declares the linkage, so the matchers should not also use {@code childOf*}.
   */
  public static void assertContainsChain(List<DecodedTrace> traces, SpanMatcher... chain) {
    long found = countChainMatches(traces, chain);
    if (found == 0) {
      throw new AssertionError(
          "No received trace contains a parent-child chain matching the "
              + chain.length
              + " given span matcher(s); traces: "
              + traces);
    }
  }

  /**
   * Counts how many received traces contain a parent-child chain matching {@code chain} (see {@link
   * #assertContainsChain(List, SpanMatcher...)} for the chain semantics). Use to verify N
   * independent operations each produced the expected trace — e.g. one distributed trace per
   * request — rather than just that a single one did.
   */
  public static long countChainMatches(List<DecodedTrace> traces, SpanMatcher... chain) {
    if (chain.length == 0) {
      throw new IllegalArgumentException("countChainMatches requires at least one span matcher");
    }
    long count = 0;
    for (DecodedTrace trace : traces) {
      if (containsChain(trace.getSpans(), chain)) {
        count++;
      }
    }
    return count;
  }

  private static boolean containsChain(List<DecodedSpan> spans, SpanMatcher[] chain) {
    for (DecodedSpan first : spans) {
      if (chain[0].rootRequired() && first.getParentId() != 0L) {
        continue;
      }
      if (chain[0].matchesFields(first) && matchesChainFrom(spans, chain, 1, first)) {
        return true;
      }
    }
    return false;
  }

  // Depth-first, backtracking: extend the chain by a direct child of `parent` matching
  // chain[index].
  private static boolean matchesChainFrom(
      List<DecodedSpan> spans, SpanMatcher[] chain, int index, DecodedSpan parent) {
    if (index == chain.length) {
      return true;
    }
    for (DecodedSpan child : spans) {
      if (child.getParentId() == parent.getSpanId()
          && chain[index].matchesFields(child)
          && matchesChainFrom(spans, chain, index + 1, child)) {
        return true;
      }
    }
    return false;
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

  /** Trace-collection matching options. */
  public static final class Options {
    boolean ignoreAdditionalTraces = false;
    Comparator<List<DecodedSpan>> sorter = null; // null => keep received order

    public Options ignoreAdditionalTraces() {
      this.ignoreAdditionalTraces = true;
      return this;
    }

    public Options sorter(Comparator<List<DecodedSpan>> sorter) {
      this.sorter = sorter;
      return this;
    }
  }
}

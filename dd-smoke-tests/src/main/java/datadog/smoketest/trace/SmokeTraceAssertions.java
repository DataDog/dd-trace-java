package datadog.smoketest.trace;

import static java.util.function.UnaryOperator.identity;

import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedTrace;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
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
 * <p>By default traces are matched <em>positionally</em> (matcher <i>i</i> ↔ trace <i>i</i>), in
 * the order received — pass {@link #SORT_BY_START_TIME} / {@link #SORT_BY_ROOT_SPAN_ID} for a
 * stable order, and the count must match exactly. Pass {@link #IGNORE_ADDITIONAL_TRACES} to switch
 * to <em>order-independent subset</em> matching instead: each matcher must match a
 * <em>distinct</em> received trace, and any extra traces are ignored — the right mode when the
 * collection contains unrelated or non-deterministically-ordered traces (e.g. a distributed test
 * where connection-setup commands land as their own traces alongside the request traces you care
 * about). The traces come from a {@code TraceBackend} (mock or test-agent), both decoded to {@link
 * DecodedTrace}.
 */
public final class SmokeTraceAssertions {
  /** Sorts traces by the earliest span start time. */
  public static final Comparator<List<DecodedSpan>> TRACE_START_TIME_COMPARATOR =
      Comparator.comparingLong(SmokeTraceAssertions::earliestStart);

  /** Sorts traces by their root span id (the span with no parent). */
  public static final Comparator<List<DecodedSpan>> TRACE_ROOT_SPAN_ID_COMPARATOR =
      Comparator.comparingLong(SmokeTraceAssertions::rootSpanId);

  /**
   * Switch to order-independent subset matching: each matcher must match a distinct received trace,
   * and extra traces are ignored (instead of the default count-exact positional matching).
   */
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
    // Copy each trace's spans to a mutable list so the matcher can sort/inspect them.
    List<List<DecodedSpan>> spanLists = new ArrayList<>(traces.size());
    for (DecodedTrace trace : traces) {
      spanLists.add(new ArrayList<>(trace.getSpans()));
    }
    if (opts.ignoreAdditionalTraces) {
      assertContainsEach(spanLists, matchers);
    } else {
      assertPositionally(spanLists, opts, matchers);
    }
  }

  // Order-independent subset: each matcher must match a distinct received trace; extras are
  // ignored.
  private static void assertContainsEach(
      List<List<DecodedSpan>> spanLists, TraceMatcher[] matchers) {
    if (matchers.length > spanLists.size()) {
      throw new AssertionError(
          "Expected at least "
              + matchers.length
              + " traces but got "
              + spanLists.size()
              + ": "
              + spanLists);
    }
    // Greedily assign each matcher to a distinct, not-yet-consumed trace. Adequate for smoke tests;
    // overlapping matchers (one strictly more general than another) could mis-assign — write them
    // specific enough to be unambiguous.
    List<List<DecodedSpan>> remaining = new ArrayList<>(spanLists);
    for (int i = 0; i < matchers.length; i++) {
      boolean matched = false;
      for (Iterator<List<DecodedSpan>> it = remaining.iterator(); it.hasNext(); ) {
        if (matchers[i].matches(it.next(), i)) {
          it.remove();
          matched = true;
          break;
        }
      }
      if (!matched) {
        throw new AssertionError(
            "No received trace matched trace matcher #"
                + i
                + " among "
                + spanLists.size()
                + " received trace(s): "
                + spanLists);
      }
    }
  }

  // Count-exact positional: matcher i ↔ trace i, after the optional trace sort.
  private static void assertPositionally(
      List<List<DecodedSpan>> spanLists, Options opts, TraceMatcher[] matchers) {
    if (spanLists.size() != matchers.length) {
      throw new AssertionError(
          "Expected " + matchers.length + " traces but got " + spanLists.size());
    }
    if (opts.sorter != null) {
      spanLists.sort(opts.sorter);
    }
    for (int i = 0; i < matchers.length; i++) {
      matchers[i].assertTrace(spanLists.get(i), i);
    }
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

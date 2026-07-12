package datadog.smoketest.trace;

import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedTrace;
import java.util.List;

/**
 * Thin trace matcher for smoke tests: one {@link SpanMatcher} per expected span in a {@link
 * DecodedTrace}. Companion of {@link SpanMatcher}; see its class doc for the serialized-model
 * rationale.
 */
public final class TraceMatcher {
  private final SpanMatcher[] spanMatchers;

  private TraceMatcher(SpanMatcher[] spanMatchers) {
    if (spanMatchers.length == 0) {
      throw new IllegalArgumentException("No span matchers provided");
    }
    this.spanMatchers = spanMatchers;
  }

  /** Checks a trace structure, one matcher per expected span. */
  public static TraceMatcher trace(SpanMatcher... matchers) {
    return new TraceMatcher(matchers);
  }

  void assertTrace(DecodedTrace trace) {
    List<DecodedSpan> spans = trace.getSpans();
    // TODO thin first cut: spans are matched positionally, in the order received. Add sorting
    //  (e.g. by start time, mirroring the instrumentation DSL) and/or find-by-id matching when a
    //  smoke test needs order-independence.
    if (spans.size() != spanMatchers.length) {
      throw new AssertionError(
          "Expected " + spanMatchers.length + " spans but got " + spans.size() + ": " + spans);
    }
    for (int i = 0; i < spanMatchers.length; i++) {
      spanMatchers[i].assertSpan(spans.get(i));
    }
  }
}

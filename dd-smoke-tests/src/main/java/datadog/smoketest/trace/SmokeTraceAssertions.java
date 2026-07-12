package datadog.smoketest.trace;

import datadog.trace.test.agent.decoder.DecodedTrace;
import java.util.List;

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
 * The traces come from a {@code TraceBackend} (mock or test-agent), both decoded to {@link
 * DecodedTrace}.
 */
public final class SmokeTraceAssertions {
  private SmokeTraceAssertions() {}

  public static void assertTraces(List<DecodedTrace> traces, TraceMatcher... matchers) {
    // TODO thin first cut: traces are matched positionally, in the order received. Add sorting
    // (e.g.
    //  by root-span start time) when a smoke test needs order-independence.
    if (traces.size() != matchers.length) {
      throw new AssertionError("Expected " + matchers.length + " traces but got " + traces.size());
    }
    for (int i = 0; i < matchers.length; i++) {
      matchers[i].assertTrace(traces.get(i));
    }
  }
}

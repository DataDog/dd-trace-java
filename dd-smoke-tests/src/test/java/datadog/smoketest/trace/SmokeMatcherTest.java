package datadog.smoketest.trace;

import static datadog.smoketest.trace.SmokeTraceAssertions.assertTraces;
import static datadog.smoketest.trace.SpanMatcher.span;
import static datadog.smoketest.trace.TraceMatcher.trace;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.smoketest.backend.TestAgentTraceDecoder;
import datadog.trace.test.agent.decoder.DecodedTrace;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Docker-free tests for the structural matcher extensions (span sorting + {@code childOfIndex} /
 * {@code childOfPrevious}, and trace-collection sorting / {@code IGNORE_ADDITIONAL_TRACES}). Traces
 * are built synthetically through the S1b JSON decoder so no backend is needed.
 */
class SmokeMatcherTest {

  // One trace, three spans forming root -> child -> grandchild, but delivered out of start order.
  private static final String CHAIN_TRACE =
      "[["
          + spanJson("grandchild", 300, 200, 30)
          + ","
          + spanJson("root", 100, 0, 10)
          + ","
          + spanJson("child", 200, 100, 20)
          + "]]";

  // Two single-span traces; root-b (id 400) has a smaller root span id than root-a (id 500).
  private static final String TWO_TRACES =
      "[[" + spanJson("root-a", 500, 0, 10) + "],[" + spanJson("root-b", 400, 0, 20) + "]]";

  // One trace whose root has two children (not a linear chain).
  private static final String BRANCHING_TRACE =
      "[["
          + spanJson("root", 100, 0, 10)
          + ","
          + spanJson("a", 200, 100, 20)
          + ","
          + spanJson("b", 300, 100, 30)
          + "]]";

  @Test
  void sortsSpansByStartTimeThenMatchesParentByPrevious() {
    List<DecodedTrace> traces = TestAgentTraceDecoder.decode(CHAIN_TRACE);
    assertTraces(
        traces,
        trace(
            TraceMatcher.SORT_BY_START_TIME,
            span().operationName("root").root(),
            span().operationName("child").childOfPrevious(),
            span().operationName("grandchild").childOfPrevious()));
  }

  @Test
  void matchesParentByIndex() {
    List<DecodedTrace> traces = TestAgentTraceDecoder.decode(CHAIN_TRACE);
    assertTraces(
        traces,
        trace(
            TraceMatcher.SORT_BY_START_TIME,
            span().operationName("root").root(),
            span().operationName("child").childOfIndex(0),
            span().operationName("grandchild").childOfIndex(1)));
  }

  @Test
  void wrongParentLinkFails() {
    List<DecodedTrace> traces = TestAgentTraceDecoder.decode(CHAIN_TRACE);
    // grandchild's parent is child (index 1), not root (index 0).
    assertThrows(
        AssertionError.class,
        () ->
            assertTraces(
                traces,
                trace(
                    TraceMatcher.SORT_BY_START_TIME,
                    span().operationName("root").root(),
                    span().operationName("child").childOfIndex(0),
                    span().operationName("grandchild").childOfIndex(0))));
  }

  @Test
  void ignoresAdditionalTraces() {
    List<DecodedTrace> traces = TestAgentTraceDecoder.decode(TWO_TRACES);
    // Assert just the first received trace, ignoring the other.
    assertTraces(
        traces,
        SmokeTraceAssertions.IGNORE_ADDITIONAL_TRACES,
        trace(span().operationName("root-a").root()));
  }

  @Test
  void sortsTracesByRootSpanId() {
    List<DecodedTrace> traces = TestAgentTraceDecoder.decode(TWO_TRACES);
    // Root-id order is [root-b (400), root-a (500)], the reverse of the received order.
    assertTraces(
        traces,
        SmokeTraceAssertions.SORT_BY_ROOT_SPAN_ID,
        trace(span().operationName("root-b").root()),
        trace(span().operationName("root-a").root()));
  }

  @Test
  void sortsByParentChainRegardlessOfStartTime() {
    List<DecodedTrace> traces = TestAgentTraceDecoder.decode(CHAIN_TRACE);
    // Spans arrive out of start order; parent-chain order recovers root -> child -> grandchild.
    assertTraces(
        traces,
        trace(
            TraceMatcher.SORT_BY_PARENT_CHAIN,
            span().operationName("root").root(),
            span().operationName("child").childOfPrevious(),
            span().operationName("grandchild").childOfPrevious()));
  }

  @Test
  void parentChainRejectsNonLinearTrace() {
    List<DecodedTrace> traces = TestAgentTraceDecoder.decode(BRANCHING_TRACE);
    // root has two children, so a chain linearization is undefined -> loud failure, not mis-order.
    assertThrows(
        IllegalStateException.class,
        () ->
            assertTraces(
                traces,
                trace(
                    TraceMatcher.SORT_BY_PARENT_CHAIN,
                    span().operationName("root").root(),
                    span().operationName("a").childOfPrevious(),
                    span().operationName("b").childOfPrevious())));
  }

  @Test
  void unorderedMatchesAnyOrder() {
    List<DecodedTrace> traces = TestAgentTraceDecoder.decode(TWO_TRACES);
    // Matchers in the opposite order of receipt still each find their trace (no sorter => any
    // order).
    assertTraces(
        traces,
        options -> options.unordered().ignoreAdditionalTraces(),
        trace(span().operationName("root-b").root()),
        trace(span().operationName("root-a").root()));
  }

  @Test
  void unorderedRequiresDistinctTraces() {
    List<DecodedTrace> traces = TestAgentTraceDecoder.decode(TWO_TRACES);
    // Two matchers for the same trace can't both match: only one root-a trace exists.
    assertThrows(
        AssertionError.class,
        () ->
            assertTraces(
                traces,
                options -> options.unordered().ignoreAdditionalTraces(),
                trace(span().operationName("root-a").root()),
                trace(span().operationName("root-a").root())));
  }

  @Test
  void unorderedWithSorterIsForwardOnly() {
    List<DecodedTrace> traces = TestAgentTraceDecoder.decode(TWO_TRACES);
    // Sorted by root span id => [root-b (400), root-a (500)]. With a sorter, an unordered match is
    // forward-only: matchers in sorted order match...
    assertTraces(
        traces,
        options -> options.unordered().sorter(SmokeTraceAssertions.TRACE_ROOT_SPAN_ID_COMPARATOR),
        trace(span().operationName("root-b").root()),
        trace(span().operationName("root-a").root()));
    // ...but reversed they don't (root-a is matched at position 1, leaving nothing after it).
    assertThrows(
        AssertionError.class,
        () ->
            assertTraces(
                traces,
                options ->
                    options.unordered().sorter(SmokeTraceAssertions.TRACE_ROOT_SPAN_ID_COMPARATOR),
                trace(span().operationName("root-a").root()),
                trace(span().operationName("root-b").root())));
  }

  private static String spanJson(String name, long id, long parent, long start) {
    return "{\"service\":\"s\",\"name\":\""
        + name
        + "\",\"resource\":\""
        + name
        + "\",\"type\":\"web\",\"trace_id\":1,\"span_id\":"
        + id
        + ",\"parent_id\":"
        + parent
        + ",\"start\":"
        + start
        + ",\"duration\":1,\"error\":0,\"meta\":{},\"metrics\":{}}";
  }
}

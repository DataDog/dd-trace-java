package datadog.smoketest.trace;

import static datadog.smoketest.trace.SmokeTraceAssertions.IGNORE_ADDITIONAL_TRACES;
import static datadog.smoketest.trace.SmokeTraceAssertions.assertTraces;
import static datadog.smoketest.trace.SpanMatcher.span;
import static datadog.smoketest.trace.TraceMatcher.SORT_BY_ANCESTRY;
import static datadog.smoketest.trace.TraceMatcher.SORT_BY_START_TIME;
import static datadog.smoketest.trace.TraceMatcher.trace;
import static datadog.trace.test.junit.utils.assertions.Matchers.isNonNull;
import static datadog.trace.test.junit.utils.assertions.Matchers.validates;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.test.agent.decoder.DecodedTrace;
import datadog.trace.test.agent.decoder.Decoder;
import java.util.List;
import java.util.Map;
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

  // One span carrying meta_struct: an IAST-style nested "_dd.stack" plus a request-body entry.
  private static final String META_STRUCT_TRACE =
      "[[{"
          + "\"service\":\"s\",\"name\":\"servlet.request\",\"resource\":\"r\","
          + "\"trace_id\":1,\"span_id\":1,\"parent_id\":0,\"start\":0,\"duration\":1,\"error\":0,"
          + "\"meta\":{},\"metrics\":{},"
          + "\"meta_struct\":{"
          + "\"_dd.stack\":{\"vulnerability\":[{\"type\":\"SQL_INJECTION\"},{\"type\":\"XSS\"}]},"
          + "\"http.request.body\":{\"foo\":\"bar\"}"
          + "}}]]";

  @Test
  void sortsSpansByStartTimeThenMatchesParentByPrevious() {
    List<DecodedTrace> traces = Decoder.decodeJson(CHAIN_TRACE).getTraces();
    assertTraces(
        traces,
        trace(
            SORT_BY_START_TIME,
            span().operationName("root").root(),
            span().operationName("child").childOfPrevious(),
            span().operationName("grandchild").childOfPrevious()));
  }

  @Test
  void matchesParentByIndex() {
    List<DecodedTrace> traces = Decoder.decodeJson(CHAIN_TRACE).getTraces();
    assertTraces(
        traces,
        trace(
            SORT_BY_START_TIME,
            span().operationName("root").root(),
            span().operationName("child").childOfIndex(0),
            span().operationName("grandchild").childOfIndex(1)));
  }

  @Test
  void wrongParentLinkFails() {
    List<DecodedTrace> traces = Decoder.decodeJson(CHAIN_TRACE).getTraces();
    // grandchild's parent is child (index 1), not root (index 0).
    assertThrows(
        AssertionError.class,
        () ->
            assertTraces(
                traces,
                trace(
                    SORT_BY_START_TIME,
                    span().operationName("root").root(),
                    span().operationName("child").childOfIndex(0),
                    span().operationName("grandchild").childOfIndex(0))));
  }

  @Test
  void ignoresAdditionalTraces() {
    List<DecodedTrace> traces = Decoder.decodeJson(TWO_TRACES).getTraces();
    // Assert just the first received trace, ignoring the other.
    assertTraces(traces, IGNORE_ADDITIONAL_TRACES, trace(span().operationName("root-a").root()));
  }

  @Test
  void sortsByAncestryRegardlessOfStartTime() {
    List<DecodedTrace> traces = Decoder.decodeJson(CHAIN_TRACE).getTraces();
    // Spans arrive out of start order; ancestry order recovers root -> child -> grandchild.
    assertTraces(
        traces,
        trace(
            SORT_BY_ANCESTRY,
            span().operationName("root").root(),
            span().operationName("child").childOfPrevious(),
            span().operationName("grandchild").childOfPrevious()));
  }

  @Test
  void ancestryOrdersBranchingTraceParentBeforeChildrenByStart() {
    List<DecodedTrace> traces = Decoder.decodeJson(BRANCHING_TRACE).getTraces();
    // root, then its two children in start order (a@20 before b@30); both are children of root.
    assertTraces(
        traces,
        trace(
            SORT_BY_ANCESTRY,
            span().operationName("root").root(),
            span().operationName("a").childOfIndex(0),
            span().operationName("b").childOfIndex(0)));
  }

  @Test
  void unorderedMatchesAnyOrder() {
    List<DecodedTrace> traces = Decoder.decodeJson(TWO_TRACES).getTraces();
    // Matchers in the opposite order of receipt still each find their trace (no sorter => any
    // order).
    assertTraces(
        traces,
        options -> options.unorder().ignoreAdditionalTraces(),
        trace(span().operationName("root-b").root()),
        trace(span().operationName("root-a").root()));
  }

  @Test
  void unorderedRequiresDistinctTraces() {
    List<DecodedTrace> traces = Decoder.decodeJson(TWO_TRACES).getTraces();
    // Two matchers for the same trace can't both match: only one root-a trace exists.
    assertThrows(
        AssertionError.class,
        () ->
            assertTraces(
                traces,
                options -> options.unorder().ignoreAdditionalTraces(),
                trace(span().operationName("root-a").root()),
                trace(span().operationName("root-a").root())));
  }

  @Test
  void matchesMetaStructByMatcherAndPredicate() {
    List<DecodedTrace> traces = Decoder.decodeJson(META_STRUCT_TRACE).getTraces();
    assertTraces(
        traces,
        trace(
            span()
                .operationName("servlet.request")
                .root()
                // plain matcher: the entry is present (any nested value)
                .metaStruct("http.request.body", isNonNull())
                // predicate via validates(): navigate the nested structure
                .metaStruct(
                    "_dd.stack",
                    validates(v -> ((List<?>) ((Map<?, ?>) v).get("vulnerability")).size() == 2))));
  }

  @Test
  void metaStructMismatchFails() {
    List<DecodedTrace> traces = Decoder.decodeJson(META_STRUCT_TRACE).getTraces();
    // Absent entry -> null value -> matcher fails.
    assertThrows(
        AssertionError.class,
        () -> assertTraces(traces, trace(span().root().metaStruct("does.not.exist", isNonNull()))));
    // Present entry but the predicate over its nested value is not satisfied.
    assertThrows(
        AssertionError.class,
        () ->
            assertTraces(
                traces,
                trace(
                    span()
                        .root()
                        .metaStruct(
                            "_dd.stack",
                            validates(
                                v ->
                                    ((List<?>) ((Map<?, ?>) v).get("vulnerability")).size()
                                        == 99)))));
  }

  @Test
  void ignoreAdditionalConsumesMatchedTrace() {
    // Two matchers demanding the same trace must not both be satisfied by a single matching trace:
    // the extra (root-b) is ignored, so once root-a is consumed the second matcher has nothing
    // left.
    List<DecodedTrace> traces = Decoder.decodeJson(TWO_TRACES).getTraces();
    assertThrows(
        AssertionError.class,
        () ->
            assertTraces(
                traces,
                IGNORE_ADDITIONAL_TRACES,
                trace(span().operationName("root-a").root()),
                trace(span().operationName("root-a").root())));
  }

  @Test
  void childOfPreviousDoesNotLeakAcrossCandidates() {
    // A broken chain (2 spans) is tried first: its childOfPrevious span resolves a concrete parent
    // id and then fails. The valid chain that follows uses different span ids and must still match
    // —
    // the matcher must not carry the first candidate's ids over to the next.
    String broken = "[" + spanJson("root", 10, 0, 10) + "," + spanJson("child", 11, 999, 20) + "]";
    String valid = "[" + spanJson("root", 20, 0, 10) + "," + spanJson("child", 21, 20, 20) + "]";
    List<DecodedTrace> traces = Decoder.decodeJson("[" + broken + "," + valid + "]").getTraces();
    assertTraces(
        traces,
        options -> options.unorder().ignoreAdditionalTraces(),
        trace(
            span().operationName("root").root(), span().operationName("child").childOfPrevious()));
  }

  @Test
  void defaultConstraintsRequireDefinedServiceNoTypeAndNoError() {
    // A bare span() enforces the default conditions: service defined, no span type, not errored.
    assertTraces(
        Decoder.decodeJson("[[" + spanJson("op", 1, 0, 0) + "]]").getTraces(),
        trace(span().root()));

    // A non-null span type fails the default unless acknowledged with type(...).
    List<DecodedTrace> typed = Decoder.decodeJson(spanTrace("s", "web", 0)).getTraces();
    assertThrows(AssertionError.class, () -> assertTraces(typed, trace(span().root())));
    assertTraces(typed, trace(span().root().type("web")));

    // An errored span fails the default unless acknowledged with error(true).
    List<DecodedTrace> errored = Decoder.decodeJson(spanTrace("s", null, 1)).getTraces();
    assertThrows(AssertionError.class, () -> assertTraces(errored, trace(span().root())));
    assertTraces(errored, trace(span().root().error(true)));

    // An undefined (empty) service fails the default.
    List<DecodedTrace> noService = Decoder.decodeJson(spanTrace("", null, 0)).getTraces();
    assertThrows(AssertionError.class, () -> assertTraces(noService, trace(span().root())));
  }

  /** A single-span trace with a configurable service, span type (nullable), and error flag. */
  private static String spanTrace(String service, String type, int error) {
    return "[[{\"service\":\""
        + service
        + "\",\"name\":\"op\",\"resource\":\"op\","
        + (type == null ? "" : "\"type\":\"" + type + "\",")
        + "\"trace_id\":1,\"span_id\":1,\"parent_id\":0,\"start\":0,\"duration\":1,\"error\":"
        + error
        + ",\"meta\":{},\"metrics\":{}}]]";
  }

  private static String spanJson(String name, long id, long parent, long start) {
    return "{\"service\":\"s\",\"name\":\""
        + name
        + "\",\"resource\":\""
        + name
        + "\",\"trace_id\":1,\"span_id\":"
        + id
        + ",\"parent_id\":"
        + parent
        + ",\"start\":"
        + start
        + ",\"duration\":1,\"error\":0,\"meta\":{},\"metrics\":{}}";
  }
}

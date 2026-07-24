package datadog.smoketest.backend;

import static datadog.smoketest.trace.SmokeTraceAssertions.assertTraces;
import static datadog.smoketest.trace.SpanMatcher.span;
import static datadog.smoketest.trace.TraceMatcher.trace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedTrace;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TestAgentTraceDecoder}: the JSON {@code /test/traces} body must decode into
 * the same {@link DecodedTrace}/{@link DecodedSpan} model the msgpack {@code Decoder} yields for
 * the mock backend, so the thin smoke matcher ({@code datadog.smoketest.trace}) works uniformly
 * across both backends (S1b / Q1).
 */
class TestAgentTraceDecoderTest {

  // A representative /test/traces body: an array of traces, each an array of v0.4-shaped spans.
  private static final String TWO_TRACES =
      "["
          + "  ["
          + "    {"
          + "      \"service\": \"my-service\","
          + "      \"name\": \"servlet.request\","
          + "      \"resource\": \"GET /greeting\","
          + "      \"type\": \"web\","
          + "      \"trace_id\": 1234567890,"
          + "      \"span_id\": 111,"
          + "      \"parent_id\": 0,"
          + "      \"start\": 1600000000000000000,"
          + "      \"duration\": 500000,"
          + "      \"error\": 0,"
          + "      \"meta\": {\"http.method\": \"GET\", \"http.status_code\": \"200\"},"
          + "      \"metrics\": {\"_dd.top_level\": 1, \"_dd.agent_psr\": 0.75}"
          + "    },"
          + "    {"
          + "      \"service\": \"my-service\","
          + "      \"name\": \"repository.query\","
          + "      \"resource\": \"SELECT users\","
          + "      \"type\": \"sql\","
          + "      \"trace_id\": 1234567890,"
          + "      \"span_id\": 222,"
          + "      \"parent_id\": 111,"
          + "      \"start\": 1600000000000100000,"
          + "      \"duration\": 200000,"
          + "      \"error\": 1,"
          + "      \"meta\": {\"db.type\": \"postgres\"},"
          + "      \"metrics\": {}"
          + "    }"
          + "  ],"
          + "  ["
          + "    {"
          + "      \"service\": \"batch\","
          + "      \"name\": \"scheduled.job\","
          + "      \"resource\": \"nightly\","
          + "      \"type\": \"custom\","
          + "      \"trace_id\": 42,"
          + "      \"span_id\": 7,"
          + "      \"parent_id\": 0,"
          + "      \"start\": 1,"
          + "      \"duration\": 1,"
          + "      \"error\": 0,"
          + "      \"meta\": {},"
          + "      \"metrics\": {}"
          + "    }"
          + "  ]"
          + "]";

  @Test
  void decodesTraceAndSpanStructure() {
    List<DecodedTrace> traces = TestAgentTraceDecoder.decode(TWO_TRACES);

    assertEquals(2, traces.size(), "trace count");
    assertEquals(2, traces.get(0).getSpans().size(), "spans in first trace");
    assertEquals(1, traces.get(1).getSpans().size(), "spans in second trace");

    DecodedSpan root = traces.get(0).getSpans().get(0);
    assertEquals("my-service", root.getService());
    assertEquals("servlet.request", root.getName());
    assertEquals("GET /greeting", root.getResource());
    assertEquals("web", root.getType());
    assertEquals(1234567890L, root.getTraceId());
    assertEquals(111L, root.getSpanId());
    assertEquals(0L, root.getParentId());
    assertEquals(1600000000000000000L, root.getStart());
    assertEquals(500000L, root.getDuration());
    assertEquals(0, root.getError());
  }

  @Test
  void mapsMetaAsStringsAndMetricsAsNumbers() {
    List<DecodedSpan> spans = TestAgentTraceDecoder.decode(TWO_TRACES).get(0).getSpans();

    Map<String, String> meta = spans.get(0).getMeta();
    assertEquals("GET", meta.get("http.method"));
    assertEquals("200", meta.get("http.status_code"));

    Map<String, Number> metrics = spans.get(0).getMetrics();
    // Moshi decodes every JSON number in the metrics map as a Double, matching the Number model.
    assertEquals(1.0, metrics.get("_dd.top_level").doubleValue());
    assertEquals(0.75, metrics.get("_dd.agent_psr").doubleValue());

    // A serialized error span carries error != 0; the parent link is the enclosing root span id.
    DecodedSpan child = spans.get(1);
    assertEquals(1, child.getError());
    assertEquals(111L, child.getParentId());
    assertEquals("postgres", child.getMeta().get("db.type"));
  }

  @Test
  void metaStructIsNullWhenAbsentAndAMapWhenPresent() {
    // Absent meta_struct decodes to null (matching the msgpack Decoder's plain-span shape).
    DecodedSpan withoutMetaStruct =
        TestAgentTraceDecoder.decode(TWO_TRACES).get(0).getSpans().get(0);
    assertNull(withoutMetaStruct.getMetaStruct());

    String withMetaStruct =
        "[[{"
            + "\"service\": \"s\", \"name\": \"n\", \"resource\": \"r\", \"type\": \"web\","
            + "\"trace_id\": 1, \"span_id\": 1, \"parent_id\": 0, \"start\": 0, \"duration\": 0,"
            + "\"error\": 0, \"meta\": {}, \"metrics\": {},"
            + "\"meta_struct\": {\"appsec\": {\"triggers\": 3}}"
            + "}]]";
    Map<String, Object> metaStruct =
        TestAgentTraceDecoder.decode(withMetaStruct).get(0).getSpans().get(0).getMetaStruct();
    assertNotNull(metaStruct);
    assertTrue(metaStruct.containsKey("appsec"));
  }

  @Test
  void emptyAndNullBodiesYieldNoTraces() {
    assertTrue(TestAgentTraceDecoder.decode("[]").isEmpty(), "empty array => no traces");
    // Moshi parses the JSON literal null as a null document; decode tolerates it.
    assertTrue(TestAgentTraceDecoder.decode("null").isEmpty(), "null body => no traces");

    List<DecodedTrace> oneEmptyTrace = TestAgentTraceDecoder.decode("[[]]");
    assertEquals(1, oneEmptyTrace.size());
    assertTrue(oneEmptyTrace.get(0).getSpans().isEmpty(), "empty trace => no spans");
  }

  @Test
  void decodedTracesFeedTheSmokeMatcher() {
    // The point of S1b: the test-agent-decoded traces are matchable by the same thin smoke DSL the
    // mock backend uses, since both produce DecodedTrace/DecodedSpan.
    List<DecodedTrace> traces = TestAgentTraceDecoder.decode(TWO_TRACES);

    assertTraces(
        traces,
        trace(
            span()
                .service("my-service")
                .operationName("servlet.request")
                .resourceName("GET /greeting")
                .type("web")
                .error(false)
                .root()
                .tag("http.method", "GET"),
            span()
                .service("my-service")
                .operationName("repository.query")
                .resourceName("SELECT users")
                .error(true)
                .childOf(111L)),
        trace(span().service("batch").operationName("scheduled.job").root()));
  }

  @Test
  void malformedJsonThrows() {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class, () -> TestAgentTraceDecoder.decode("{ not valid json"));
    assertTrue(e.getMessage().contains("/test/traces"), "message should reference the endpoint");
  }
}

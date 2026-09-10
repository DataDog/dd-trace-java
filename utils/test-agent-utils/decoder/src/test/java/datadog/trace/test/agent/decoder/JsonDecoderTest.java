package datadog.trace.test.agent.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Decoder#decodeJson(String)}: the dd-apm-test-agent JSON trace body (an array of
 * traces, each an array of v0.4-shaped spans) must decode into the same {@link DecodedTrace}/{@link
 * DecodedSpan} model the msgpack decoders produce.
 */
class JsonDecoderTest {

  // A representative JSON trace body (an array of traces, each an array of v0.4-shaped spans), kept
  // as a resource file rather than an inline literal for readability (folding, syntax
  // highlighting).
  private static final String TWO_TRACES = loadJson("/two-traces.json");

  @Test
  void decodesTraceAndSpanStructure() {
    List<DecodedTrace> traces = Decoder.decodeJson(TWO_TRACES).getTraces();

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
    List<DecodedSpan> spans = Decoder.decodeJson(TWO_TRACES).getTraces().get(0).getSpans();

    Map<String, String> meta = spans.get(0).getMeta();
    assertEquals("GET", meta.get("http.method"));
    assertEquals("200", meta.get("http.status_code"));

    Map<String, Number> metrics = spans.get(0).getMetrics();
    // Integral metrics decode to Integer and fractional metrics to Double, matching the msgpack
    // decoders (SpanV04.unpackNumber) so an assertion behaves identically across both backends.
    assertEquals(1, metrics.get("_dd.top_level"));
    assertEquals(0.75, metrics.get("_dd.agent_psr"));

    // A serialized error span carries error != 0; the parent link is the enclosing root span id.
    DecodedSpan child = spans.get(1);
    assertEquals(1, child.getError());
    assertEquals(111L, child.getParentId());
    assertEquals("postgres", child.getMeta().get("db.type"));
  }

  @Test
  void metaStructEmptyWhenAbsentAndAMapWhenPresent() {
    // Absent meta_struct decodes to an empty map, matching the msgpack decoders (SpanV04).
    DecodedSpan withoutMetaStruct =
        Decoder.decodeJson(TWO_TRACES).getTraces().get(0).getSpans().get(0);
    assertTrue(withoutMetaStruct.getMetaStruct().isEmpty());

    String withMetaStruct = loadJson("/with-meta-struct.json");
    Map<String, Object> metaStruct =
        Decoder.decodeJson(withMetaStruct).getTraces().get(0).getSpans().get(0).getMetaStruct();
    assertTrue(metaStruct.containsKey("appsec"));
  }

  @Test
  void emptyAndNullBodiesYieldNoTraces() {
    assertTrue(Decoder.decodeJson("[]").getTraces().isEmpty(), "empty array => no traces");
    // Moshi parses the JSON literal null as a null document; decode tolerates it.
    assertTrue(Decoder.decodeJson("null").getTraces().isEmpty(), "null body => no traces");

    List<DecodedTrace> oneEmptyTrace = Decoder.decodeJson("[[]]").getTraces();
    assertEquals(1, oneEmptyTrace.size());
    assertTrue(oneEmptyTrace.get(0).getSpans().isEmpty(), "empty trace => no spans");
  }

  @Test
  void decodesUnsignedIds() {
    // Trace/span IDs are unsigned 64-bit; the agent emits them as JSON numbers that can exceed
    // Long.MAX_VALUE. They must be parsed unsigned and kept as the signed bit pattern.
    String maxUnsigned = Long.toUnsignedString(-1L); // 18446744073709551615 == 2^64 - 1
    String json =
        "[[{"
            + "\"service\": \"s\", \"name\": \"n\", \"resource\": \"r\", \"type\": \"web\","
            + "\"trace_id\": "
            + maxUnsigned
            + ", \"span_id\": "
            + maxUnsigned
            + ", \"parent_id\": 0, \"start\": 0, \"duration\": 0, \"error\": 0,"
            + "\"meta\": {}, \"metrics\": {}"
            + "}]]";
    DecodedSpan span = Decoder.decodeJson(json).getTraces().get(0).getSpans().get(0);
    assertEquals(-1L, span.getTraceId(), "unsigned 2^64-1 kept as its signed bit pattern");
    assertEquals(-1L, span.getSpanId());
    assertEquals(0L, span.getParentId());
  }

  @Test
  void preservesIntegralMetricTypesAndPrecision() {
    // Integral metrics that fit an int stay Integer; larger integrals stay Long (exact, not rounded
    // through Double); integrals beyond long range stay BigInteger (still exact, not Double);
    // fractional metrics stay Double — matching SpanV04/SpanV05.unpackNumber so a metric assertion
    // behaves identically whether the span came from the msgpack or JSON backend.
    long aboveDoublePrecision = (1L << 53) + 1; // 9007199254740993, not representable as a double
    BigInteger beyondLong = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE); // 2^63
    String json =
        "[[{"
            + "\"service\": \"s\", \"name\": \"n\", \"resource\": \"r\","
            + "\"trace_id\": 1, \"span_id\": 1, \"parent_id\": 0, \"start\": 0, \"duration\": 0,"
            + "\"error\": 0, \"meta\": {},"
            + "\"metrics\": {\"small\": 1, \"big\": "
            + aboveDoublePrecision
            + ", \"huge\": "
            + beyondLong
            + ", \"ratio\": 0.5}"
            + "}]]";
    Map<String, Number> metrics =
        Decoder.decodeJson(json).getTraces().get(0).getSpans().get(0).getMetrics();

    assertEquals(1, metrics.get("small"), "int-range integral stays Integer");
    assertEquals(aboveDoublePrecision, metrics.get("big"), "large integral stays Long, exact");
    assertEquals(beyondLong, metrics.get("huge"), "beyond-long integral stays BigInteger, exact");
    assertEquals(0.5, metrics.get("ratio"), "fractional stays Double");
  }

  @Test
  void rejectsSpansMissingRequiredV04Fields() {
    // The agent's Span schema marks name/trace_id/span_id/start/duration as required; a truncated
    // or
    // schema-mismatched response omitting any of them must fail loudly rather than decode into a
    // span whose fields silently default to 0/null.
    assertThrows(
        IllegalStateException.class,
        () ->
            Decoder.decodeJson(oneSpan("\"trace_id\":1,\"span_id\":1,\"start\":0,\"duration\":0")),
        "missing name");
    assertThrows(
        IllegalStateException.class,
        () ->
            Decoder.decodeJson(oneSpan("\"name\":\"n\",\"span_id\":1,\"start\":0,\"duration\":0")),
        "missing trace_id");
    assertThrows(
        IllegalStateException.class,
        () ->
            Decoder.decodeJson(oneSpan("\"name\":\"n\",\"trace_id\":1,\"start\":0,\"duration\":0")),
        "missing span_id");
    assertThrows(
        IllegalStateException.class,
        () ->
            Decoder.decodeJson(
                oneSpan("\"name\":\"n\",\"trace_id\":1,\"span_id\":1,\"duration\":0")),
        "missing start");
    assertThrows(
        IllegalStateException.class,
        () ->
            Decoder.decodeJson(oneSpan("\"name\":\"n\",\"trace_id\":1,\"span_id\":1,\"start\":0")),
        "missing duration");

    // error and parent_id are optional per the agent schema: a span omitting both still decodes,
    // with error defaulting to 0 (no error) and parent_id to 0 (root).
    DecodedSpan span =
        Decoder.decodeJson(
                oneSpan("\"name\":\"n\",\"trace_id\":1,\"span_id\":1,\"start\":0,\"duration\":0"))
            .getTraces()
            .get(0)
            .getSpans()
            .get(0);
    assertEquals(0, span.getError(), "absent error => 0");
    assertEquals(0L, span.getParentId(), "absent parent_id => 0 (root)");
  }

  @Test
  void rejectsNullTraceEntry() {
    // A null trace element ([null]) is corruption, not an empty trace; fail rather than silently
    // decode it into an empty trace that still inflates the trace count.
    assertThrows(IllegalStateException.class, () -> Decoder.decodeJson("[null]"));
  }

  @Test
  void decodedTraceListIsUnmodifiable() {
    // Matches the msgpack DecodedMessage implementations, which all expose an unmodifiable list.
    List<DecodedTrace> traces = Decoder.decodeJson(TWO_TRACES).getTraces();
    assertThrows(UnsupportedOperationException.class, traces::clear);
  }

  /** Wraps a single span's JSON fields into a one-trace, one-span payload. */
  private static String oneSpan(String fields) {
    return "[[{" + fields + "}]]";
  }

  /** Reads a JSON test fixture from the classpath (kept as a resource for readability). */
  private static String loadJson(String resourceName) {
    try (InputStream in = JsonDecoderTest.class.getResourceAsStream(resourceName)) {
      if (in == null) {
        throw new IllegalStateException("Missing test resource: " + resourceName);
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      return out.toString("UTF-8");
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read test resource: " + resourceName, e);
    }
  }

  @Test
  void preservesMetaStructNumericTypesAcrossNesting() {
    // meta_struct numeric leaves must decode to the same types the msgpack decoder produces
    // (SpanV04.convertValueToObject): integers -> Long, floating-point -> Float, recursively
    // through
    // nested objects and arrays, with strings/booleans/null preserved. Otherwise an exact
    // metaStruct(...) assertion or a leaf cast would behave differently across the two backends.
    long aboveDoublePrecision = (1L << 53) + 1; // 9007199254740993, not representable as a double
    BigInteger beyondLong = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE); // 2^63
    String json =
        "[[{"
            + "\"service\": \"s\", \"name\": \"n\", \"resource\": \"r\","
            + "\"trace_id\": 1, \"span_id\": 1, \"parent_id\": 0, \"start\": 0, \"duration\": 0,"
            + "\"error\": 0, \"meta\": {}, \"metrics\": {},"
            + "\"meta_struct\": {\"appsec\": {"
            + "  \"count\": 3,"
            + "  \"rate\": 0.5,"
            + "  \"whole\": 3.0,"
            + "  \"nested\": {\"big\": "
            + aboveDoublePrecision
            + ", \"huge\": "
            + beyondLong
            + "},"
            + "  \"list\": [1, 2.5, \"x\", true, null]"
            + "}}"
            + "}]]";
    Map<String, Object> metaStruct =
        Decoder.decodeJson(json).getTraces().get(0).getSpans().get(0).getMetaStruct();

    @SuppressWarnings("unchecked")
    Map<String, Object> appsec = (Map<String, Object>) metaStruct.get("appsec");
    assertEquals(3L, appsec.get("count"), "integer leaf -> Long");
    assertEquals(0.5F, appsec.get("rate"), "float leaf -> Float");
    // A whole-number float: the live agent emits it as "3.0" (json.dumps keeps the decimal point),
    // so it must box to Float like the msgpack FLOAT leaf, not Long. Verified via a real
    // round-trip.
    assertEquals(3.0F, appsec.get("whole"), "whole-number float keeps .0 -> Float");

    @SuppressWarnings("unchecked")
    Map<String, Object> nested = (Map<String, Object>) appsec.get("nested");
    assertEquals(aboveDoublePrecision, nested.get("big"), "large integer stays Long, exact");
    assertEquals(beyondLong, nested.get("huge"), "beyond-long integer stays BigInteger, exact");

    @SuppressWarnings("unchecked")
    List<Object> list = (List<Object>) appsec.get("list");
    assertEquals(1L, list.get(0), "integer in array -> Long");
    assertEquals(2.5F, list.get(1), "float in array -> Float");
    assertEquals("x", list.get(2), "string in array preserved");
    assertEquals(Boolean.TRUE, list.get(3), "boolean in array preserved");
    assertNull(list.get(4), "null leaf preserved");
  }

  @Test
  void malformedJsonThrows() {
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> Decoder.decodeJson("{ not valid json"));
    assertTrue(e.getMessage().contains("{ not valid json"), "message should include the body");
  }
}

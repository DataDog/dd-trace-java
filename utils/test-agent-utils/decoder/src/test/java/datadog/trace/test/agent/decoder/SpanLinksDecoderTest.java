package datadog.trace.test.agent.decoder;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpanLinksDecoderTest {
  // The decoder keeps the tag name private, so spell it out the way the tracer writes it.
  private static final String SPAN_LINKS_TAG = "_dd.span_links";
  private static final String TRACE_ID_128 = "11223344556677889900aabbccddeeff";
  private static final long TRACE_ID_LOW = 0x9900aabbccddeeffL;

  @Test
  void absentOrEmptyTagYieldsNoLink() {
    assertTrue(DecodedSpanLinks.fromMeta(emptyMap()).isEmpty(), "no tag in meta");
    assertTrue(fromTag(null).isEmpty(), "null tag");
    assertTrue(fromTag("").isEmpty(), "empty tag");
    assertTrue(fromTag("[]").isEmpty(), "empty array");
    assertTrue(fromTag("null").isEmpty(), "JSON null");
  }

  @Test
  void decodesMinimalLinkApplyingDefaults() {
    List<DecodedSpanLink> links =
        fromTag("[{\"span_id\":\"123456789abcdef0\",\"trace_id\":\"" + TRACE_ID_128 + "\"}]");

    assertEquals(1, links.size());
    DecodedSpanLink link = links.get(0);
    assertEquals(TRACE_ID_LOW, link.getTraceId(), "a 128-bit id narrows to its low-order half");
    assertEquals(0x123456789abcdef0L, link.getSpanId());
    assertEquals((byte) 0, link.getTraceFlags(), "absent flags default to 0");
    assertEquals("", link.getTraceState(), "absent tracestate defaults to empty");
    assertTrue(link.getAttributes().isEmpty(), "absent attributes default to empty");
  }

  @Test
  void decodesFullLink() {
    List<DecodedSpanLink> links =
        fromTag(
            "[{\"trace_id\":\""
                + TRACE_ID_128
                + "\",\"span_id\":\"a\",\"flags\":1,"
                + "\"tracestate\":\"dd=s:1\","
                + "\"attributes\":{\"link.kind\":\"span-pointer\",\"ptr.dir\":\"d\"}}]");

    DecodedSpanLink link = links.get(0);
    assertEquals(0xaL, link.getSpanId(), "an unpadded span id decodes");
    assertEquals((byte) 1, link.getTraceFlags());
    assertEquals("dd=s:1", link.getTraceState());
    Map<String, String> expected = new HashMap<>();
    expected.put("link.kind", "span-pointer");
    expected.put("ptr.dir", "d");
    assertEquals(expected, link.getAttributes());
  }

  @Test
  void preservesLinkOrderAndDecodesUnsignedSpanIds() {
    List<DecodedSpanLink> links =
        fromTag(
            "[{\"trace_id\":\"0\",\"span_id\":\"1\"},"
                + "{\"trace_id\":\"0\",\"span_id\":\"ffffffffffffffff\"},"
                + "{\"trace_id\":\"0\",\"span_id\":\"2\"}]");

    assertEquals(3, links.size());
    assertEquals(1L, links.get(0).getSpanId());
    assertEquals(-1L, links.get(1).getSpanId(), "an unsigned span id above Long.MAX_VALUE");
    assertEquals(2L, links.get(2).getSpanId());
  }

  @Test
  void decodesAShortTraceId() {
    DecodedSpanLink link = fromTag("[{\"trace_id\":\"abcd\",\"span_id\":\"1\"}]").get(0);

    assertEquals(0xabcdL, link.getTraceId());
  }

  @Test
  void unknownKeysAreIgnored() {
    DecodedSpanLink link =
        fromTag("[{\"trace_id\":\"1\",\"span_id\":\"1\",\"something_new\":\"whatever\"}]").get(0);

    assertEquals(1L, link.getTraceId());
  }

  @Test
  void malformedTagsThrow() {
    assertThrows(IllegalStateException.class, () -> fromTag("not json"), "malformed JSON");
    assertThrows(
        IllegalStateException.class, () -> fromTag("[{\"span_id\":\"1\"}]"), "missing trace_id");
    assertThrows(
        IllegalStateException.class, () -> fromTag("[{\"trace_id\":\"1\"}]"), "missing span_id");
    assertThrows(
        IllegalStateException.class,
        () -> fromTag("[{\"trace_id\":\"1\",\"span_id\":\"zz\"}]"),
        "malformed span id");
    assertThrows(IllegalStateException.class, () -> fromTag("[null]"), "null link entry");
  }

  @Test
  void linkFactoryNormalizesAbsentValues() {
    DecodedSpanLink link = DecodedSpanLinks.link(2L, 3L, (byte) 4, null, null);

    assertEquals(2L, link.getTraceId());
    assertEquals(3L, link.getSpanId());
    assertEquals((byte) 4, link.getTraceFlags());
    assertEquals("", link.getTraceState());
    assertTrue(link.getAttributes().isEmpty());

    DecodedSpanLink withValues =
        DecodedSpanLinks.link(0L, 0L, (byte) 0, "dd=s:2", singletonMap("k", "v"));
    assertEquals("dd=s:2", withValues.getTraceState());
    assertEquals(singletonMap("k", "v"), withValues.getAttributes());
  }

  @Test
  void rendersIdentifiersAsHexadecimal() {
    String rendered = DecodedSpanLinks.link(TRACE_ID_LOW, 0xaL, (byte) 0, "", null).toString();

    assertTrue(rendered.contains("traceId=9900aabbccddeeff"), "renders the trace id: " + rendered);
    assertTrue(rendered.contains("spanId=a"), "renders the span id: " + rendered);
  }

  @Test
  void treatsAnExplicitlyEmptyAttributeMapAsNone() {
    DecodedSpanLink link =
        DecodedSpanLinks.link(0L, 0L, (byte) 0, "", java.util.Collections.emptyMap());

    assertTrue(link.getAttributes().isEmpty());
  }

  private static List<DecodedSpanLink> fromTag(String tagValue) {
    return DecodedSpanLinks.fromMeta(singletonMap(SPAN_LINKS_TAG, tagValue));
  }

  @Test
  void decodedSpanExposesLinksAndKeepsTheTagVisible() {
    String tag = "[{\\\"trace_id\\\":\\\"" + TRACE_ID_128 + "\\\",\\\"span_id\\\":\\\"a\\\"}]";
    String json =
        "[[{\"service\":\"s\",\"name\":\"n\",\"resource\":\"r\","
            + "\"trace_id\":1,\"span_id\":1,\"parent_id\":0,\"start\":0,\"duration\":1,\"error\":0,"
            + "\"metrics\":{},\"meta\":{\""
            + SPAN_LINKS_TAG
            + "\":\""
            + tag
            + "\"}}]]";

    DecodedSpan span = Decoder.decodeJson(json).getTraces().get(0).getSpans().get(0);

    assertEquals(1, span.getLinks().size());
    assertEquals(0xaL, span.getLinks().get(0).getSpanId());
    assertNotNull(
        span.getMeta().get(SPAN_LINKS_TAG), "the tag stays visible as the untyped meta entry");
  }

  @Test
  void decodesStructuredSpanLinksFieldFromAV1Payload() {
    // The agent converts a v1.0 payload's structured links into a top-level span_links field,
    // with decimal identifiers, rather than into the meta tag.
    String json =
        "[[{\"service\":\"s\",\"name\":\"n\",\"resource\":\"r\","
            + "\"trace_id\":1,\"span_id\":1,\"parent_id\":0,\"start\":0,\"duration\":1,\"error\":0,"
            + "\"metrics\":{},\"meta\":{},"
            + "\"span_links\":[{\"trace_id\":11068046444225730559,\"trace_id_high\":1234,"
            + "\"span_id\":10,\"flags\":1,\"tracestate\":\"dd=s:1\","
            + "\"attributes\":{\"link.kind\":\"span-pointer\"}}]}]]";

    DecodedSpan span = Decoder.decodeJson(json).getTraces().get(0).getSpans().get(0);

    assertEquals(1, span.getLinks().size());
    DecodedSpanLink link = span.getLinks().get(0);
    assertEquals(
        Long.parseUnsignedLong("11068046444225730559"),
        link.getTraceId(),
        "an unsigned id above Long.MAX_VALUE survives");
    assertEquals(10L, link.getSpanId());
    assertEquals((byte) 1, link.getTraceFlags());
    assertEquals("dd=s:1", link.getTraceState());
    assertEquals(singletonMap("link.kind", "span-pointer"), link.getAttributes());
  }

  @Test
  void structuredSpanLinksFieldWinsOverTheMetaTag() {
    String json =
        "[[{\"service\":\"s\",\"name\":\"n\",\"resource\":\"r\","
            + "\"trace_id\":1,\"span_id\":1,\"parent_id\":0,\"start\":0,\"duration\":1,\"error\":0,"
            + "\"metrics\":{},\"meta\":{\""
            + SPAN_LINKS_TAG
            + "\":\"[{\\\"trace_id\\\":\\\"1\\\",\\\"span_id\\\":\\\"99\\\"}]\"},"
            + "\"span_links\":[{\"trace_id\":1,\"span_id\":7}]}]]";

    DecodedSpan span = Decoder.decodeJson(json).getTraces().get(0).getSpans().get(0);

    assertEquals(1, span.getLinks().size());
    assertEquals(7L, span.getLinks().get(0).getSpanId(), "the structured field is preferred");
  }

  @Test
  void anEmptyStructuredSpanLinksFieldWinsOverTheMetaTag() {
    // A v1.0 payload always carries the span_links field, so an empty one means the span has no
    // link, whichever legacy meta tag the payload also holds.
    String json =
        "[[{\"service\":\"s\",\"name\":\"n\",\"resource\":\"r\","
            + "\"trace_id\":1,\"span_id\":1,\"parent_id\":0,\"start\":0,\"duration\":1,\"error\":0,"
            + "\"metrics\":{},\"meta\":{\""
            + SPAN_LINKS_TAG
            + "\":\"[{\\\"trace_id\\\":\\\"1\\\",\\\"span_id\\\":\\\"99\\\"}]\"},"
            + "\"span_links\":[]}]]";

    DecodedSpan span = Decoder.decodeJson(json).getTraces().get(0).getSpans().get(0);

    assertTrue(span.getLinks().isEmpty(), "the structured field is preferred, even when empty");
  }

  @Test
  void decodedSpanWithoutTheTagExposesNoLink() {
    String json =
        "[[{\"service\":\"s\",\"name\":\"n\",\"resource\":\"r\","
            + "\"trace_id\":1,\"span_id\":1,\"parent_id\":0,\"start\":0,\"duration\":1,\"error\":0,"
            + "\"metrics\":{},\"meta\":{}}]]";

    DecodedSpan span = Decoder.decodeJson(json).getTraces().get(0).getSpans().get(0);

    assertTrue(span.getLinks().isEmpty());
  }
}

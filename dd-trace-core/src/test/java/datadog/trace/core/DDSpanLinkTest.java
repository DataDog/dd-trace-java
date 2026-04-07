package datadog.trace.core;

import static datadog.trace.api.DDTags.SPAN_LINKS;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpanLink.DEFAULT_FLAGS;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpanLink.SAMPLED_FLAG;
import static datadog.trace.bootstrap.instrumentation.api.SpanAttributes.EMPTY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.DynamicConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors;
import datadog.trace.bootstrap.instrumentation.api.SpanAttributes;
import datadog.trace.bootstrap.instrumentation.api.SpanLink;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.HttpCodec;
import datadog.trace.core.propagation.HttpCodecTestHelper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.tabletest.junit.TableTest;

class DDSpanLinkTest extends DDCoreJavaSpecification {

  private static final int SPAN_LINK_TAG_MAX_LENGTH = 25_000;
  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
  // W3C Trace Context standard header names (W3CHttpCodec is package-private)
  private static final String TRACE_PARENT_KEY = "traceparent";
  private static final String TRACE_STATE_KEY = "tracestate";

  private ListWriter writer = new ListWriter();
  private CoreTracer tracer = tracerBuilder().writer(writer).build();

  @AfterEach
  void cleanupTest() {
    writer.clear();
  }

  @TableTest({
    "scenario    | sampled | traceFlags | sample",
    "sampled     | true    | '01'       | '1'   ",
    "not sampled | false   | '00'       | '-1'  "
  })
  @ParameterizedTest(name = "create span link from extracted context [{index}]")
  void createSpanLinkFromExtractedContext(boolean sampled, String traceFlags, String sample) {
    String traceId = "11223344556677889900aabbccddeeff";
    String spanId = "123456789abcdef0";
    String traceState = "dd=s:" + sample + ";o:some;t.dm:-4";
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_PARENT_KEY.toUpperCase(), "00-" + traceId + "-" + spanId + "-" + traceFlags);
    headers.put(TRACE_STATE_KEY.toUpperCase(), traceState);
    HttpCodec.Extractor extractor =
        HttpCodecTestHelper.W3CHttpCodecNewExtractor(
            Config.get(), () -> DynamicConfig.create().apply().captureTraceConfig());

    ExtractedContext context =
        (ExtractedContext) extractor.extract(headers, ContextVisitors.stringValuesMap());
    SpanLink link = DDSpanLink.from(context);

    assertEquals(DDTraceId.fromHex(traceId), link.traceId());
    assertEquals(DDSpanId.fromHex(spanId), link.spanId());
    assertEquals(sampled ? SAMPLED_FLAG : DEFAULT_FLAGS, link.traceFlags());
    assertEquals(traceState + ";t.tid:" + traceId.substring(0, 16), link.traceState());
  }

  @Test
  void testSpanLinkEncodingTagMaxSize() throws Exception {
    int tooManyLinkCount = 300;
    AgentTracer.SpanBuilder builder = tracer.buildSpan("test", "operation");
    List<SpanLink> links =
        IntStream.range(0, tooManyLinkCount)
            .mapToObj(this::createLink)
            .collect(Collectors.toList());

    for (SpanLink link : links) {
      builder.withLink(link);
    }
    AgentSpan span = builder.start();
    span.finish();
    writer.waitForTraces(1);
    String spanLinksTag = (String) writer.get(0).get(0).getTag(SPAN_LINKS);
    List<TestSpanLinkJson> decodedSpanLinks =
        JSON_MAPPER.readValue(
            spanLinksTag,
            JSON_MAPPER
                .getTypeFactory()
                .constructCollectionType(List.class, TestSpanLinkJson.class));

    assertTrue(spanLinksTag.length() < SPAN_LINK_TAG_MAX_LENGTH);
    assertTrue(decodedSpanLinks.size() < tooManyLinkCount);
    assertTrue(
        (double) spanLinksTag.length() / decodedSpanLinks.size() * (decodedSpanLinks.size() + 1)
            > SPAN_LINK_TAG_MAX_LENGTH);
    for (int i = 0; i < decodedSpanLinks.size(); i++) {
      assertLink(links.get(i), decodedSpanLinks.get(i));
    }
  }

  @Test
  void testSpanLinksEncodingOmittedEmptyKeys() throws Exception {
    AgentTracer.SpanBuilder builder = tracer.buildSpan("test", "operation");
    SpanLink link =
        new DDSpanLink(
            DDTraceId.fromHex("11223344556677889900aabbccddeeff"),
            DDSpanId.fromHex("123456789abcdef0"),
            DEFAULT_FLAGS,
            "",
            EMPTY);

    AgentSpan span = builder.withLink(link).start();
    span.finish();
    writer.waitForTraces(1);
    String spanLinksTag = (String) writer.get(0).get(0).getTag(SPAN_LINKS);

    assertEquals(
        "[{\"span_id\":\"123456789abcdef0\",\"trace_id\":\"11223344556677889900aabbccddeeff\"}]",
        spanLinksTag);
  }

  @TableTest({
    "scenario               | beforeStart | afterStart",
    "no links               | false       | false     ",
    "link before start only | true        | false     ",
    "link after start only  | false       | true      ",
    "links before and after | true        | true      "
  })
  @ParameterizedTest(name = "add span link at any time [{index}]")
  void addSpanLinkAtAnyTime(boolean beforeStart, boolean afterStart) throws Exception {
    AgentTracer.SpanBuilder builder = tracer.buildSpan("test", "operation");
    List<SpanLink> links = new java.util.ArrayList<>();

    if (beforeStart) {
      SpanLink link = createLink(0);
      builder.withLink(link);
      links.add(link);
    }
    AgentSpan span = builder.start();
    if (afterStart) {
      SpanLink link = createLink(1);
      span.addLink(link);
      links.add(link);
    }
    span.finish();
    writer.waitForTraces(1);
    String spanLinksTag = (String) writer.get(0).get(0).getTag(SPAN_LINKS);
    List<TestSpanLinkJson> decodedSpanLinks =
        spanLinksTag == null
            ? java.util.Collections.emptyList()
            : JSON_MAPPER.readValue(
                spanLinksTag,
                JSON_MAPPER
                    .getTypeFactory()
                    .constructCollectionType(List.class, TestSpanLinkJson.class));

    assertEquals((beforeStart ? 1 : 0) + (afterStart ? 1 : 0), decodedSpanLinks.size());
    for (int i = 0; i < decodedSpanLinks.size(); i++) {
      assertLink(links.get(i), decodedSpanLinks.get(i));
    }
  }

  @Test
  void filterNullLinks() throws Exception {
    AgentTracer.SpanBuilder builder = tracer.buildSpan("test", "operation");

    AgentSpan span = builder.withLink(null).start();
    span.addLink(null);
    span.finish();
    writer.waitForTraces(1);
    String spanLinksTag = (String) writer.get(0).get(0).getTag(SPAN_LINKS);

    assertNull(spanLinksTag);
  }

  private SpanLink createLink(int index) {
    Map<String, String> attributes = new HashMap<>();
    attributes.put("link-index", Integer.toString(index));

    return new DDSpanLink(
        DDTraceId.fromHex(String.format("11223344556677889900aabbccdd%04d", index)),
        DDSpanId.fromHex(String.format("123456789abc%04d", index)),
        index % 2 == 0 ? SAMPLED_FLAG : DEFAULT_FLAGS,
        "",
        SpanAttributes.fromMap(attributes));
  }

  private void assertLink(SpanLink expected, TestSpanLinkJson actual) {
    assertEquals(expected.traceId().toHexString(), actual.trace_id);
    assertEquals(DDSpanId.toHexString(expected.spanId()), actual.span_id);
    if (expected.traceFlags() == DEFAULT_FLAGS) {
      assertNull(actual.flags);
    } else {
      assertEquals(expected.traceFlags(), actual.flags);
    }
    if (expected.traceState().isEmpty()) {
      assertNull(actual.tracestate);
    } else {
      assertEquals(expected.traceState(), actual.trace_id);
    }
    if (expected.attributes().isEmpty()) {
      assertNull(actual.attributes);
    } else {
      assertEquals(expected.attributes().asMap(), actual.attributes);
    }
  }

  static class TestSpanLinkJson {
    public String trace_id;
    public String span_id;
    public Byte flags;
    public String tracestate;
    public Map<String, String> attributes;
  }
}

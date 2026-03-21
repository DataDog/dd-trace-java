package datadog.trace.core;

import static datadog.trace.api.DDTags.SPAN_LINKS;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpanLink.DEFAULT_FLAGS;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpanLink.SAMPLED_FLAG;
import static datadog.trace.bootstrap.instrumentation.api.SpanAttributes.EMPTY;
import static datadog.trace.core.propagation.W3CHttpCodec.TRACE_PARENT_KEY;
import static datadog.trace.core.propagation.W3CHttpCodec.TRACE_STATE_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.DynamicConfig;
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors;
import datadog.trace.bootstrap.instrumentation.api.SpanAttributes;
import datadog.trace.bootstrap.instrumentation.api.SpanLink;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.HttpCodec;
import datadog.trace.core.propagation.W3CHttpCodec;
import datadog.trace.core.test.DDCoreSpecification;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class DDSpanLinkTest extends DDCoreSpecification {

  static ListWriter sharedWriter;
  static CoreTracer sharedTracer;

  @BeforeAll
  static void setupShared() {
    sharedWriter = new ListWriter();
    // tracerBuilder() is non-static, so we use a basic builder here
    sharedTracer = CoreTracer.builder().writer(sharedWriter).build();
  }

  @AfterAll
  static void cleanupShared() throws Exception {
    if (sharedTracer != null) {
      sharedTracer.close();
    }
  }

  @ParameterizedTest
  @CsvSource({"true", "false"})
  void createSpanLinkFromExtractedContext(boolean sampled) throws Exception {
    String traceId = "11223344556677889900aabbccddeeff";
    String spanId = "123456789abcdef0";
    String traceFlags = sampled ? "01" : "00";
    String sample = sampled ? "1" : "-1";
    String traceState = "dd=s:" + sample + ";o:some;t.dm:-4";

    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_PARENT_KEY.toUpperCase(), "00-" + traceId + "-" + spanId + "-" + traceFlags);
    headers.put(TRACE_STATE_KEY.toUpperCase(), traceState);

    HttpCodec.Extractor extractor =
        W3CHttpCodec.newExtractor(
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
    CoreTracer.CoreSpanBuilder builder = sharedTracer.buildSpan("test", "operation");
    List<SpanLink> links =
        IntStream.range(0, tooManyLinkCount)
            .mapToObj(this::createLink)
            .collect(Collectors.toList());

    for (SpanLink link : links) {
      builder.withLink(link);
    }
    datadog.trace.bootstrap.instrumentation.api.AgentSpan span = builder.start();
    span.finish();
    DDSpan ddSpan = (DDSpan) span;
    sharedWriter.waitUntilReported(ddSpan);
    String spanLinksTag = (String) ddSpan.getTags().get(SPAN_LINKS);
    List<Map<String, Object>> decodedSpanLinks = parseJsonArray(spanLinksTag);

    assertTrue(spanLinksTag.length() < DDSpanLink.TAG_MAX_LENGTH);
    assertTrue(decodedSpanLinks.size() < tooManyLinkCount);
    assertTrue(
        (double) spanLinksTag.length() / decodedSpanLinks.size() * (decodedSpanLinks.size() + 1)
            > DDSpanLink.TAG_MAX_LENGTH);
    for (int i = 0; i < decodedSpanLinks.size(); i++) {
      assertLinkJson(links.get(i), decodedSpanLinks.get(i));
    }
  }

  @Test
  void testSpanLinksEncodingOmittedEmptyKeys() throws Exception {
    CoreTracer.CoreSpanBuilder builder = sharedTracer.buildSpan("test", "operation");
    SpanLink link =
        new DDSpanLink(
            DDTraceId.fromHex("11223344556677889900aabbccddeeff"),
            DDSpanId.fromHex("123456789abcdef0"),
            DEFAULT_FLAGS,
            "",
            EMPTY);

    datadog.trace.bootstrap.instrumentation.api.AgentSpan span = builder.withLink(link).start();
    span.finish();
    DDSpan ddSpan = (DDSpan) span;
    sharedWriter.waitUntilReported(ddSpan);
    String spanLinksTag = (String) ddSpan.getTags().get(SPAN_LINKS);

    assertEquals(
        "[{\"span_id\":\"123456789abcdef0\",\"trace_id\":\"11223344556677889900aabbccddeeff\"}]",
        spanLinksTag);
  }

  @ParameterizedTest
  @CsvSource({"false, false", "true, false", "false, true", "true, true"})
  void addSpanLinkAtAnyTime(boolean beforeStart, boolean afterStart) throws Exception {
    CoreTracer.CoreSpanBuilder builder = sharedTracer.buildSpan("test", "operation");
    List<SpanLink> links = new java.util.ArrayList<>();

    if (beforeStart) {
      SpanLink link = createLink(0);
      builder.withLink(link);
      links.add(link);
    }
    datadog.trace.bootstrap.instrumentation.api.AgentSpan span = builder.start();
    if (afterStart) {
      SpanLink link = createLink(1);
      span.addLink(link);
      links.add(link);
    }
    span.finish();
    DDSpan ddSpan = (DDSpan) span;
    sharedWriter.waitUntilReported(ddSpan);
    String spanLinksTag = (String) ddSpan.getTags().get(SPAN_LINKS);
    List<Map<String, Object>> decodedSpanLinks =
        spanLinksTag == null ? java.util.Collections.emptyList() : parseJsonArray(spanLinksTag);

    int expectedSize = (beforeStart ? 1 : 0) + (afterStart ? 1 : 0);
    assertEquals(expectedSize, decodedSpanLinks.size());
    for (int i = 0; i < decodedSpanLinks.size(); i++) {
      assertLinkJson(links.get(i), decodedSpanLinks.get(i));
    }
  }

  @Test
  void filterNullLinks() throws Exception {
    CoreTracer.CoreSpanBuilder builder = sharedTracer.buildSpan("test", "operation");

    datadog.trace.bootstrap.instrumentation.api.AgentSpan span = builder.withLink(null).start();
    span.addLink(null);
    span.finish();
    DDSpan ddSpan = (DDSpan) span;
    sharedWriter.waitUntilReported(ddSpan);
    String spanLinksTag = (String) ddSpan.getTags().get(SPAN_LINKS);

    assertNull(spanLinksTag);
  }

  SpanLink createLink(int index) {
    Map<String, String> attributes = new HashMap<>();
    attributes.put("link-index", Integer.toString(index));

    return new DDSpanLink(
        DDTraceId.fromHex(String.format("11223344556677889900aabbccdd%04d", index)),
        DDSpanId.fromHex(String.format("123456789abc%04d", index)),
        index % 2 == 0 ? SAMPLED_FLAG : DEFAULT_FLAGS,
        "",
        SpanAttributes.fromMap(attributes));
  }

  void assertLinkJson(SpanLink expected, Map<String, Object> actual) {
    assertEquals(expected.traceId().toHexString(), actual.get("trace_id"));
    assertEquals(DDSpanId.toHexString(expected.spanId()), actual.get("span_id"));
    if (expected.traceFlags() == DEFAULT_FLAGS) {
      assertNull(actual.get("flags"));
    } else {
      assertEquals((int) expected.traceFlags(), actual.get("flags"));
    }
    if (expected.traceState().isEmpty()) {
      assertNull(actual.get("tracestate"));
    } else {
      assertEquals(expected.traceState(), actual.get("tracestate"));
    }
    if (expected.attributes().isEmpty()) {
      assertNull(actual.get("attributes"));
    } else {
      assertEquals(expected.attributes().asMap(), actual.get("attributes"));
    }
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> parseJsonArray(String json) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    return mapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
  }
}

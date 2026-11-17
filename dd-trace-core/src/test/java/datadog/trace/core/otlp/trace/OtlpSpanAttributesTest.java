package datadog.trace.core.otlp.trace;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import datadog.json.JsonMapper;
import datadog.trace.api.Config;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.MockedStatic;

/**
 * End-to-end check that the global tags are exported once, as OTLP resource attributes taken from
 * the trace's config snapshot, while span defaults and the span's own tags stay on each span.
 * Traces with different global tags share a payload under resources of their own.
 */
@WithConfig(key = GeneralConfig.TAGS, value = "team:core,region:us1")
@WithConfig(key = TracerConfig.SPAN_TAGS, value = "owner:alice,tier:gold")
class OtlpSpanAttributesTest {

  private CoreTracer tracer;

  @BeforeEach
  void setUp() {
    tracer = CoreTracer.builder().writer(new ListWriter()).build();
  }

  @AfterEach
  void tearDown() {
    tracer.close();
  }

  @Test
  void globalTagsOnlyOnResource() throws IOException {
    DDSpan span = (DDSpan) tracer.startSpan("test", "op");
    span.setTag("http.method", "GET");
    span.setTag("region", "eu1"); // a span's own value for a global tag is kept
    span.setTag("tier", "silver"); // a span's own value for a span default replaces it
    span.finish();

    Map<String, Object> resourceSpans = collect(span);

    Map<String, Object> resourceAttributes = attributes(resource(resourceSpans));
    assertEquals("core", resourceAttributes.get("team"));
    assertEquals("us1", resourceAttributes.get("region"));

    Map<String, Object> spanAttributes = attributes(onlySpan(resourceSpans));
    assertFalse(spanAttributes.containsKey("team"));
    assertEquals("eu1", spanAttributes.get("region"));
    assertEquals("alice", spanAttributes.get("owner"));
    assertEquals("silver", spanAttributes.get("tier"));
    assertEquals("GET", spanAttributes.get("http.method"));
  }

  @Test
  void removedSpanDefaultIsNotWritten() throws IOException {
    DDSpan span = (DDSpan) tracer.startSpan("test", "op");
    span.setTag("owner", (String) null); // removes the span default
    span.finish();

    Map<String, Object> spanAttributes = attributes(onlySpan(collect(span)));
    assertFalse(spanAttributes.containsKey("owner"));
    assertEquals("gold", spanAttributes.get("tier"));
  }

  /** A span default that interception consumes into a span field isn't written again as a tag. */
  @Test
  @WithConfig(key = TracerConfig.SPAN_TAGS, value = "owner:alice,resource.name:configured")
  void consumedSpanDefaultIsNotWrittenTwice() throws IOException {
    CoreTracer interceptingTracer = CoreTracer.builder().writer(new ListWriter()).build();
    try {
      DDSpan span = (DDSpan) interceptingTracer.startSpan("test", "op");
      span.finish();

      // attributes() fails on a duplicate resource.name
      Map<String, Object> spanAttributes = attributes(onlySpan(collect(span)));
      assertEquals("configured", spanAttributes.get("resource.name"));
      assertEquals("alice", spanAttributes.get("owner"));
    } finally {
      interceptingTracer.close();
    }
  }

  /** Under OTel semantics span.kind is left to the OTLP kind field, even as a span default. */
  @Test
  @WithConfig(key = TracerConfig.SPAN_TAGS, value = "owner:alice,span.kind:server")
  void spanKindDefaultIsNotWrittenUnderOtelSemantics() throws IOException {
    Config realConfig = Config.get();
    try (MockedStatic<Config> configMock = mockStatic(Config.class)) {
      Config config = mock(Config.class, AdditionalAnswers.delegatesTo(realConfig));
      when(config.isTraceOtelSemanticsEnabled()).thenReturn(true);
      configMock.when(Config::get).thenReturn(config);

      CoreTracer kindTracer = CoreTracer.builder().writer(new ListWriter()).build();
      try {
        DDSpan span = (DDSpan) kindTracer.startSpan("test", "op");
        span.finish();

        Map<String, Object> otlpSpan = onlySpan(collect(span));
        assertEquals(2, ((Number) otlpSpan.get("kind")).intValue(), "SPAN_KIND_SERVER = 2");
        Map<String, Object> spanAttributes = attributes(otlpSpan);
        assertFalse(spanAttributes.containsKey("span.kind"));
        assertEquals("alice", spanAttributes.get("owner"));
      } finally {
        kindTracer.close();
      }
    }
  }

  @Test
  void traceWithOtherGlobalTagsGetsItsOwnResource() throws IOException {
    CoreTracer otherTracer = CoreTracer.builder().writer(new ListWriter()).build();
    try {
      // another tracer's snapshot holds its own global tags map
      List<List<CoreSpan<?>>> traces =
          asList(
              droppedTrace(otherTracer), // no exported spans, so no resource of its own
              finishedTrace(tracer),
              finishedTrace(tracer),
              finishedTrace(otherTracer),
              finishedTrace(tracer));

      OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
      traces.forEach(collector::addTrace);
      List<Map<String, Object>> resourceSpans = allResourceSpans(collector.collectTraces());

      assertEquals(3, resourceSpans.size());
      assertEquals(2, spans(resourceSpans.get(0)).size());
      assertEquals(1, spans(resourceSpans.get(1)).size());
      assertEquals(1, spans(resourceSpans.get(2)).size());
      for (Map<String, Object> resource : resourceSpans) {
        assertEquals("core", attributes(resource(resource)).get("team"));
      }
    } finally {
      otherTracer.close();
    }
  }

  @Test
  void traceWithOtherGlobalTagsGetsItsOwnProtoResource() throws IOException {
    CoreTracer otherTracer = CoreTracer.builder().writer(new ListWriter()).build();
    try {
      List<List<CoreSpan<?>>> traces =
          asList(
              droppedTrace(otherTracer), // no exported spans, so no resource of its own
              finishedTrace(tracer),
              finishedTrace(tracer),
              finishedTrace(otherTracer),
              finishedTrace(tracer));

      OtlpTraceProtoCollector collector = new OtlpTraceProtoCollector();
      traces.forEach(collector::addTrace);
      OtlpPayload payload = collector.collectTraces();

      // the protobuf payload is prepended, so its resources come out last first
      assertEquals(asList(1, 1, 2), protoSpansPerResource(payload));
    } finally {
      otherTracer.close();
    }
  }

  private static Map<String, Object> collect(DDSpan span) throws IOException {
    OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
    collector.addTrace(asList((CoreSpan<?>) span));
    return onlyResourceSpans(collector.collectTraces());
  }

  private static List<CoreSpan<?>> finishedTrace(CoreTracer tracer) {
    DDSpan span = (DDSpan) tracer.startSpan("test", "op");
    span.finish();
    return asList((CoreSpan<?>) span);
  }

  private static List<CoreSpan<?>> droppedTrace(CoreTracer tracer) {
    DDSpan span = (DDSpan) tracer.startSpan("test", "op");
    span.setSamplingPriority(PrioritySampling.USER_DROP);
    span.finish();
    return asList((CoreSpan<?>) span);
  }

  private static Map<String, Object> onlyResourceSpans(OtlpPayload payload) throws IOException {
    List<Map<String, Object>> resourceSpans = allResourceSpans(payload);
    assertEquals(1, resourceSpans.size());
    return resourceSpans.get(0);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> allResourceSpans(OtlpPayload payload)
      throws IOException {
    byte[] bytes = new byte[payload.getContentLength()];
    payload.getContent().get(bytes);
    Map<String, Object> root = JsonMapper.fromJsonToMap(new String(bytes, StandardCharsets.UTF_8));
    return (List<Map<String, Object>>) root.get("resourceSpans");
  }

  /** Counts the spans in each TracesData.resource_spans entry of a protobuf payload. */
  private static List<Integer> protoSpansPerResource(OtlpPayload payload) throws IOException {
    List<Integer> counts = new ArrayList<>();
    CodedInputStream tracesData = CodedInputStream.newInstance(payload.getContent());
    while (!tracesData.isAtEnd()) {
      assertEquals(1, WireFormat.getTagFieldNumber(tracesData.readTag())); // resource_spans
      CodedInputStream resourceSpans = tracesData.readBytes().newCodedInput();
      int count = 0;
      while (!resourceSpans.isAtEnd()) {
        int tag = resourceSpans.readTag();
        if (WireFormat.getTagFieldNumber(tag) != 2) { // scope_spans
          resourceSpans.skipField(tag);
          continue;
        }
        CodedInputStream scopeSpans = resourceSpans.readBytes().newCodedInput();
        while (!scopeSpans.isAtEnd()) {
          int scopeTag = scopeSpans.readTag();
          if (WireFormat.getTagFieldNumber(scopeTag) == 2) { // spans
            count++;
          }
          scopeSpans.skipField(scopeTag);
        }
      }
      counts.add(count);
    }
    return counts;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> resource(Map<String, Object> resourceSpans) {
    return (Map<String, Object>) resourceSpans.get("resource");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> onlySpan(Map<String, Object> resourceSpans) {
    List<Object> spans = spans(resourceSpans);
    assertEquals(1, spans.size());
    return (Map<String, Object>) spans.get(0);
  }

  @SuppressWarnings("unchecked")
  private static List<Object> spans(Map<String, Object> resourceSpans) {
    List<Object> scopeSpans = (List<Object>) resourceSpans.get("scopeSpans");
    return (List<Object>) ((Map<String, Object>) scopeSpans.get(0)).get("spans");
  }

  /** Flattens OTLP JSON {@code KeyValue} attributes, failing on duplicate keys. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> attributes(Map<String, Object> owner) {
    Map<String, Object> result = new HashMap<>();
    for (Object attribute : (List<Object>) owner.get("attributes")) {
      Map<String, Object> keyValue = (Map<String, Object>) attribute;
      Map<String, Object> value = (Map<String, Object>) keyValue.get("value");
      Object previous = result.put((String) keyValue.get("key"), value.values().iterator().next());
      assertNull(previous, "duplicate attribute " + keyValue.get("key"));
    }
    return result;
  }
}

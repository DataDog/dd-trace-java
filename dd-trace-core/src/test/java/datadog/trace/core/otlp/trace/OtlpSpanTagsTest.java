package datadog.trace.core.otlp.trace;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.json.JsonMapper;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end check that the global tags are exported once, as OTLP resource attributes taken from
 * the trace's config snapshot, while span defaults and the span's own tags stay on each span.
 */
@WithConfig(key = GeneralConfig.TAGS, value = "team:core,region:us1")
@WithConfig(key = TracerConfig.SPAN_TAGS, value = "owner:alice,tier:gold")
class OtlpSpanTagsTest {

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

  @Test
  void traceWithOtherGlobalTagsNeedsItsOwnPayload() {
    CoreTracer otherTracer = CoreTracer.builder().writer(new ListWriter()).build();
    try {
      List<CoreSpan<?>> trace = finishedTrace(tracer);
      List<CoreSpan<?>> sameTagsTrace = finishedTrace(tracer);
      // another tracer's snapshot holds its own global tags map
      List<CoreSpan<?>> otherTagsTrace = finishedTrace(otherTracer);

      OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
      assertTrue(collector.sharesResource(trace)); // empty payload takes any trace
      collector.addTrace(trace);
      assertTrue(collector.sharesResource(sameTagsTrace));
      assertFalse(collector.sharesResource(otherTagsTrace));

      collector.collectTraces();
      assertTrue(collector.sharesResource(otherTagsTrace)); // a new payload takes it
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

  @SuppressWarnings("unchecked")
  private static Map<String, Object> onlyResourceSpans(OtlpPayload payload) throws IOException {
    byte[] bytes = new byte[payload.getContentLength()];
    payload.getContent().get(bytes);
    Map<String, Object> root = JsonMapper.fromJsonToMap(new String(bytes, StandardCharsets.UTF_8));
    List<Object> resourceSpans = (List<Object>) root.get("resourceSpans");
    assertEquals(1, resourceSpans.size());
    return (Map<String, Object>) resourceSpans.get(0);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> resource(Map<String, Object> resourceSpans) {
    return (Map<String, Object>) resourceSpans.get("resource");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> onlySpan(Map<String, Object> resourceSpans) {
    List<Object> scopeSpans = (List<Object>) resourceSpans.get("scopeSpans");
    List<Object> spans = (List<Object>) ((Map<String, Object>) scopeSpans.get(0)).get("spans");
    assertEquals(1, spans.size());
    return (Map<String, Object>) spans.get(0);
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

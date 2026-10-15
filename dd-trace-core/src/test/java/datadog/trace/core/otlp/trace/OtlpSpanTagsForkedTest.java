package datadog.trace.core.otlp.trace;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import org.junit.jupiter.api.Test;

/**
 * End-to-end check that global tags exported as OTLP resource attributes are not repeated on each
 * span, while span-only tracer tags still are. Forked because the trace resource is built once from
 * {@code Config} on first use.
 */
@WithConfig(key = GeneralConfig.TAGS, value = "team:core,region:us1")
@WithConfig(key = TracerConfig.SPAN_TAGS, value = "owner:alice")
class OtlpSpanTagsForkedTest {

  @Test
  void globalTagsOnlyOnResource() throws IOException {
    CoreTracer tracer = CoreTracer.builder().writer(new ListWriter()).build();
    DDSpan span = (DDSpan) tracer.startSpan("test", "op");
    span.setTag("http.method", "GET");
    span.setTag("region", "eu1"); // span-level override of a global tag is kept
    span.finish();

    OtlpTraceJsonCollector collector = new OtlpTraceJsonCollector();
    collector.addTrace(asList((CoreSpan<?>) span));
    Map<String, Object> resourceSpans = parse(collector.collectTraces());

    Map<String, Object> resourceAttributes = attributes(resource(resourceSpans));
    assertEquals("core", resourceAttributes.get("team"));
    assertEquals("us1", resourceAttributes.get("region"));

    Map<String, Object> spanAttributes = attributes(onlySpan(resourceSpans));
    assertFalse(spanAttributes.containsKey("team"));
    assertEquals("eu1", spanAttributes.get("region"));
    assertEquals("alice", spanAttributes.get("owner"));
    assertEquals("GET", spanAttributes.get("http.method"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parse(OtlpPayload payload) throws IOException {
    byte[] bytes = new byte[payload.getContentLength()];
    payload.getContent().get(bytes);
    Map<String, Object> root = JsonMapper.fromJsonToMap(new String(bytes, StandardCharsets.UTF_8));
    return (Map<String, Object>) ((List<Object>) root.get("resourceSpans")).get(0);
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

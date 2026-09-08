package datadog.trace.bootstrap.instrumentation.messaging;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@code _datadog} message attribute parser shared by the AWS messaging instrumentations
 * (SQS, SNS, EventBridge, Step Functions).
 */
class DatadogAttributeParserTest {

  /** What an injected {@code _datadog} attribute looks like on the wire. */
  private static final String FULL_CONTEXT =
      "{\"x-datadog-trace-id\":\"1234567890\","
          + "\"x-datadog-parent-id\":\"9876543210\","
          + "\"x-datadog-sampling-priority\":\"1\","
          + "\"x-datadog-tags\":\"_dd.p.dm=-1,_dd.p.tid=6aa01c5400000000\","
          + "\"traceparent\":\"00-6aa01c5400000000499602d2-000000024cb016ea-01\"}";

  private static Map<String, String> parse(String json) {
    Map<String, String> collected = new LinkedHashMap<>();
    DatadogAttributeParser.forEachProperty(
        (key, value) -> {
          collected.put(key, value);
          return true;
        },
        json);
    return collected;
  }

  @Test
  void extractsTraceContextAndPropagationTags() {
    Map<String, String> collected = parse(FULL_CONTEXT);

    assertEquals("1234567890", collected.get("x-datadog-trace-id"));
    assertEquals("9876543210", collected.get("x-datadog-parent-id"));
    assertEquals("1", collected.get("x-datadog-sampling-priority"));
    // Without x-datadog-tags the whole _dd.p.* set is dropped at the messaging boundary: the
    // 64-bit trace id still joins, but _dd.p.tid is lost so the two services disagree about the
    // full 128-bit id, and _dd.p.dm is lost with it.
    assertEquals("_dd.p.dm=-1,_dd.p.tid=6aa01c5400000000", collected.get("x-datadog-tags"));
  }

  @Test
  void extractsPropagationTagsFromByteBufferCarrier() {
    Map<String, String> collected = new LinkedHashMap<>();
    DatadogAttributeParser.forEachProperty(
        (key, value) -> {
          collected.put(key, value);
          return true;
        },
        ByteBuffer.wrap(FULL_CONTEXT.getBytes(UTF_8)));

    assertEquals("_dd.p.dm=-1,_dd.p.tid=6aa01c5400000000", collected.get("x-datadog-tags"));
  }

  @Test
  void extractsPropagationTagsFromBase64ByteBufferCarrier() {
    Map<String, String> collected = new LinkedHashMap<>();
    DatadogAttributeParser.forEachProperty(
        (key, value) -> {
          collected.put(key, value);
          return true;
        },
        ByteBuffer.wrap(Base64.getEncoder().encode(FULL_CONTEXT.getBytes(UTF_8))));

    assertEquals("_dd.p.dm=-1,_dd.p.tid=6aa01c5400000000", collected.get("x-datadog-tags"));
  }

  @Test
  void carriesLlmObsPropagationTags() {
    Map<String, String> collected =
        parse(
            "{\"x-datadog-trace-id\":\"1234567890\","
                + "\"x-datadog-parent-id\":\"9876543210\","
                + "\"x-datadog-tags\":\"_dd.p.llmobs_ml_app=my-app,_dd.p.llmobs_sid=sess-1,"
                + "_dd.p.llmobs_parent_id=42\"}");

    String tags = collected.get("x-datadog-tags");
    assertTrue(tags.contains("_dd.p.llmobs_ml_app=my-app"), tags);
    assertTrue(tags.contains("_dd.p.llmobs_sid=sess-1"), tags);
    assertTrue(tags.contains("_dd.p.llmobs_parent_id=42"), tags);
  }

  @Test
  void extractsNothingWithoutATraceId() {
    // Propagation tags on their own describe no trace, so they are not surfaced.
    Map<String, String> collected =
        parse("{\"x-datadog-tags\":\"_dd.p.dm=-1\",\"x-datadog-parent-id\":\"9876543210\"}");

    assertTrue(collected.isEmpty(), () -> "expected nothing extracted, got " + collected);
  }

  @Test
  void toleratesAMissingTagsProperty() {
    Map<String, String> collected =
        parse(
            "{\"x-datadog-trace-id\":\"1234567890\",\"x-datadog-parent-id\":\"9876543210\","
                + "\"x-datadog-sampling-priority\":\"1\"}");

    assertEquals("1234567890", collected.get("x-datadog-trace-id"));
    assertNull(collected.get("x-datadog-tags"));
  }

  @Test
  void toleratesMalformedJson() {
    assertTrue(parse("not json at all").isEmpty());
    assertTrue(parse("{\"x-datadog-trace-id\":").isEmpty());
    assertTrue(parse(null).isEmpty());
  }
}

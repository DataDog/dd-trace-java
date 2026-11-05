package datadog.trace.lambda;

import static datadog.trace.lambda.LambdaEventData.json;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LambdaEventCarriersTest {
  private LambdaTrigger extract(String json) {
    return new LambdaTriggerExtractor("us-east-1", "function", emptyMap()).extract(json);
  }

  @Test
  void httpHeadersAreCaseInsensitiveAndAlbSupportsMultipleValues() {
    LambdaTrigger trigger =
        extract(
            "{\"requestContext\":{\"elb\":{\"targetGroupArn\":\"arn:elb\"}},"
                + "\"multiValueHeaders\":{\"X-Datadog-Trace-Id\":[\"42\",\"43\"]}}");
    assertEquals("42", trigger.carrier.get("x-datadog-trace-id"));
    assertNull(trigger.name);
  }

  @Test
  void sqsStringAndBinaryAttributesHaveTheSameCarrier() {
    String carrier = "{\"x-datadog-trace-id\":\"42\",\"x-datadog-parent-id\":\"7\"}";
    for (boolean binary : new boolean[] {false, true}) {
      Map<String, Object> event =
          json(
              "{\"Records\":[{\"eventSource\":\"aws:sqs\","
                  + "\"eventSourceARN\":\"arn:aws:sqs:us-east-1:123:queue\",\"messageAttributes\":{\"_datadog\":{}}}]}");
      Map<String, Object> record = LambdaEventData.first(event.get("Records"));
      Map<String, Object> attribute =
          LambdaEventData.object(LambdaEventData.get(record, "messageAttributes", "_datadog"));
      attribute.put("dataType", binary ? "Binary.custom" : "String");
      attribute.put(
          binary ? "binaryValue" : "stringValue",
          binary
              ? Base64.getEncoder().encodeToString(carrier.getBytes(StandardCharsets.UTF_8))
              : carrier);
      LambdaTrigger trigger =
          new LambdaTriggerExtractor("us-east-1", "fn", emptyMap()).extract(event);
      assertEquals("42", trigger.carrier.get("x-datadog-trace-id"));
      assertEquals("7", trigger.carrier.get("x-datadog-parent-id"));
    }
  }

  @Test
  void eventBridgeAndKinesisExposeEmbeddedContexts() {
    LambdaTrigger bridge =
        extract(
            "{\"detail-type\":\"test\",\"source\":\"custom\","
                + "\"detail\":{\"_datadog\":{\"traceparent\":\"parent\"}}}");
    assertEquals("parent", bridge.carrier.get("traceparent"));
    String data =
        Base64.getEncoder()
            .encodeToString(
                "{\"_datadog\":{\"x-datadog-trace-id\":\"42\"}}".getBytes(StandardCharsets.UTF_8));
    LambdaTrigger kinesis =
        extract(
            "{\"Records\":[{\"eventSourceARN\":\"arn:aws:kinesis:us-east-1:123:stream/test\","
                + "\"kinesis\":{\"data\":\""
                + data
                + "\"}}]}");
    assertEquals("42", kinesis.carrier.get("x-datadog-trace-id"));
  }

  @Test
  void generatedXrayPreservesUnsignedIdsAndUnsetSampling() {
    assertEquals(
        "18446744073709551615",
        LambdaGeneratedContext.xray(
                "Root=1-68029e8a-00000000ffffffffffffffff;Parent=ffffffffffffffff;Sampled=1")
            .getTraceId()
            .toString());
    assertNull(LambdaGeneratedContext.xray("Root=malformed;Parent=0"));
  }

  @Test
  void stepFunctionsProducesAParentWithoutCreatingAServiceSpan() {
    LambdaTrigger trigger =
        extract(
            "{\"Execution\":{\"Id\":\"execution\",\"RedriveCount\":0},"
                + "\"State\":{\"Name\":\"step\",\"EnteredTime\":\"2026-01-01T00:00:00Z\",\"RetryCount\":0}}");
    assertNotNull(trigger.generatedContext);
    assertNull(trigger.name);
    assertEquals("states", trigger.tags.get("function_trigger.event_source"));
  }
}

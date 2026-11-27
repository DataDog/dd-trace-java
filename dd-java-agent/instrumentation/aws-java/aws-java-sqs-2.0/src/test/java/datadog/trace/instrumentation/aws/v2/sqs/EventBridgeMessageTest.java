package datadog.trace.instrumentation.aws.v2.sqs;

import static datadog.trace.api.datastreams.DataStreamsTags.DIRECTION_TAG;
import static datadog.trace.api.datastreams.DataStreamsTags.EXCHANGE_TAG;
import static datadog.trace.api.datastreams.DataStreamsTags.TOPIC_TAG;
import static datadog.trace.api.datastreams.DataStreamsTags.TYPE_TAG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Collections;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

class EventBridgeMessageTest {
  private static final String ENVELOPE =
      "{\"version\":\"0\",\"id\":\"abc\",\"detail-type\":\"order.created\","
          + "\"source\":\"com.example\",\"detail\":{\"message\":\"hi\","
          + "\"_datadog\":{\"x-datadog-trace-id\":\"123\","
          + "\"dd-pathway-ctx-base64\":\"AAE=\","
          + "\"x-datadog-resource-name\":\"test-bus\"}}}";

  @Test
  void parsesEventBridgeEnvelopeFromBody() {
    Message message = Message.builder().body(ENVELOPE).build();
    EventBridgeMessage parsed = EventBridgeMessage.tryParseFrom(message);

    assertNotNull(parsed);
    assertEquals("123", parsed.datadogAttributes().get("x-datadog-trace-id"));
    assertEquals(DIRECTION_TAG + ":in", parsed.consumeTags().getDirection());
    assertEquals(EXCHANGE_TAG + ":test-bus", parsed.consumeTags().getExchange());
    assertEquals(TOPIC_TAG + ":order.created", parsed.consumeTags().getTopic());
    assertEquals(TYPE_TAG + ":eventbridge", parsed.consumeTags().getType());
  }

  @Test
  void normalizesArnResourceNames() {
    String body =
        "{\"detail-type\":\"order.created\",\"detail\":{\"_datadog\":"
            + "{\"x-datadog-resource-name\":"
            + "\"arn:aws:events:us-east-1:123456789012:event-bus/test-bus\"}}}";
    EventBridgeMessage parsed =
        EventBridgeMessage.tryParseFrom(Message.builder().body(body).build());

    assertNotNull(parsed);
    assertEquals(EXCHANGE_TAG + ":test-bus", parsed.consumeTags().getExchange());
  }

  @Test
  void fallsBackToTheDefaultBusWhenResourceNameIsMissing() {
    String body =
        "{\"detail-type\":\"order.created\",\"detail\":{\"_datadog\":{\"x-datadog-trace-id\":\"123\"}}}";
    EventBridgeMessage parsed =
        EventBridgeMessage.tryParseFrom(Message.builder().body(body).build());

    assertNotNull(parsed);
    assertEquals(EXCHANGE_TAG + ":default", parsed.consumeTags().getExchange());
  }

  @Test
  void returnsNullWhenDatadogAttributeIsPresent() {
    Message message =
        Message.builder()
            .body(ENVELOPE)
            .messageAttributes(
                Collections.singletonMap(
                    "_datadog",
                    MessageAttributeValue.builder().dataType("String").stringValue("{}").build()))
            .build();

    assertNull(EventBridgeMessage.tryParseFrom(message));
  }

  @Test
  void returnsNullInsteadOfStackOverflowForDeeplyNestedBodies() {
    StringBuilder body =
        new StringBuilder(
            "{\"detail-type\":\"order.created\",\"detail\":{\"_datadog\":"
                + "{\"x-datadog-trace-id\":\"123\"},\"payload\":");
    for (int i = 0; i < 100_000; i++) {
      body.append('[');
    }
    for (int i = 0; i < 100_000; i++) {
      body.append(']');
    }
    body.append("}}");

    assertNull(EventBridgeMessage.tryParseFrom(Message.builder().body(body.toString()).build()));
  }

  @Test
  void parsesBodiesWithNestingBelowTheDepthBound() {
    StringBuilder payload = new StringBuilder();
    for (int i = 0; i < 10; i++) {
      payload.append("{\"level\":");
    }
    payload.append("0");
    for (int i = 0; i < 10; i++) {
      payload.append("}");
    }
    String body =
        "{\"detail-type\":\"order.created\",\"detail\":{\"_datadog\":"
            + "{\"x-datadog-trace-id\":\"123\",\"x-datadog-resource-name\":\"test-bus\"},"
            + "\"payload\":"
            + payload
            + "}}";

    EventBridgeMessage parsed =
        EventBridgeMessage.tryParseFrom(Message.builder().body(body).build());

    assertNotNull(parsed);
    assertEquals("123", parsed.datadogAttributes().get("x-datadog-trace-id"));
  }

  @Test
  void ignoresBracesInsideStringValuesWhenCountingDepth() {
    StringBuilder noise = new StringBuilder();
    for (int i = 0; i < 600; i++) {
      noise.append('{');
    }
    String body =
        "{\"detail-type\":\"order.created\",\"detail\":{\"_datadog\":"
            + "{\"x-datadog-trace-id\":\"123\"},\"noise\":\""
            + noise
            + "\"}}";

    EventBridgeMessage parsed =
        EventBridgeMessage.tryParseFrom(Message.builder().body(body).build());

    assertNotNull(parsed);
    assertEquals("123", parsed.datadogAttributes().get("x-datadog-trace-id"));
  }

  @Test
  void returnsNullForBodiesWithoutEventBridgeContext() {
    assertNull(EventBridgeMessage.tryParseFrom(Message.builder().body(null).build()));
    assertNull(EventBridgeMessage.tryParseFrom(Message.builder().body("").build()));
    assertNull(EventBridgeMessage.tryParseFrom(Message.builder().body("plain text").build()));
    assertNull(
        EventBridgeMessage.tryParseFrom(Message.builder().body("{\"message\":\"hi\"}").build()));
    assertNull(
        EventBridgeMessage.tryParseFrom(
            Message.builder()
                .body("{\"detail-type\":\"order.created\",\"detail\":{\"message\":\"no context\"}}")
                .build()));
    assertNull(
        EventBridgeMessage.tryParseFrom(
            Message.builder()
                .body("{\"detail-type\":\"order.created\",\"detail\":{\"_datadog\":{}}}")
                .build()));
  }
}

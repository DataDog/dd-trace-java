package datadog.trace.instrumentation.aws.v2.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EventBridgeInterceptorTest {

  @Test
  void buildsDataStreamsTopicFromArnAndDetailType() {
    assertEquals(
        "test-bus:order.created",
        EventBridgeInterceptor.buildDataStreamsTopic(
            "arn:aws:events:us-east-1:123456789012:event-bus/test-bus", "order.created"));
  }

  @Test
  void keepsPartnerBusPathWhenNormalizingArn() {
    assertEquals(
        "aws.partner/example.com/acct/bus-name:detail-type",
        EventBridgeInterceptor.buildDataStreamsTopic(
            "arn:aws:events:us-east-1:123456789012:event-bus/aws.partner/example.com/acct/bus-name",
            "detail-type"));
  }

  @Test
  void defaultsToTheDefaultEventBusNameWhenMissing() {
    assertEquals(
        "default:detail-type", EventBridgeInterceptor.buildDataStreamsTopic(null, "detail-type"));
  }

  @Test
  void omitsTheSeparatorWhenDetailTypeIsMissing() {
    assertEquals("test-bus", EventBridgeInterceptor.buildDataStreamsTopic("test-bus", null));
  }
}

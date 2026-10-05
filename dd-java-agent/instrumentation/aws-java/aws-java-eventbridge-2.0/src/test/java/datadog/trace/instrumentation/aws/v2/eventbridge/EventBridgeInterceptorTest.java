package datadog.trace.instrumentation.aws.v2.eventbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.datastreams.DataStreamsTags;
import org.junit.jupiter.api.Test;

class EventBridgeInterceptorTest {

  @Test
  void buildsDataStreamsTagsFromArnAndDetailType() {
    DataStreamsTags tags =
        EventBridgeInterceptor.buildDataStreamsTags(
            "arn:aws:events:us-east-1:123456789012:event-bus/test-bus", "order.created");

    assertEquals(DataStreamsTags.DIRECTION_TAG + ":out", tags.getDirection());
    assertEquals(DataStreamsTags.EXCHANGE_TAG + ":test-bus", tags.getExchange());
    assertEquals(DataStreamsTags.TOPIC_TAG + ":order.created", tags.getTopic());
    assertEquals(DataStreamsTags.TYPE_TAG + ":eventbridge", tags.getType());
  }

  @Test
  void keepsPartnerBusPathWhenNormalizingArn() {
    DataStreamsTags tags =
        EventBridgeInterceptor.buildDataStreamsTags(
            "arn:aws:events:us-east-1:123456789012:event-bus/aws.partner/example.com/acct/bus-name",
            "detail-type");

    assertEquals(
        DataStreamsTags.EXCHANGE_TAG + ":aws.partner/example.com/acct/bus-name",
        tags.getExchange());
    assertEquals(DataStreamsTags.TOPIC_TAG + ":detail-type", tags.getTopic());
  }

  @Test
  void defaultsToTheDefaultEventBusNameWhenMissing() {
    DataStreamsTags tags = EventBridgeInterceptor.buildDataStreamsTags(null, "detail-type");

    assertEquals(DataStreamsTags.EXCHANGE_TAG + ":default", tags.getExchange());
    assertEquals(DataStreamsTags.TOPIC_TAG + ":detail-type", tags.getTopic());
  }

  @Test
  void omitsTheTopicTagWhenDetailTypeIsMissing() {
    DataStreamsTags tags = EventBridgeInterceptor.buildDataStreamsTags("test-bus", null);

    assertEquals(DataStreamsTags.EXCHANGE_TAG + ":test-bus", tags.getExchange());
    assertNull(tags.getTopic());
  }
}

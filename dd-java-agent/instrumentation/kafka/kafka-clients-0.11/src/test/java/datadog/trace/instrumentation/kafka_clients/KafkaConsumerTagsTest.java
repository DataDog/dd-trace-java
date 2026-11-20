package datadog.trace.instrumentation.kafka_clients;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.CONSUMER_GROUP;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.KAFKA_CLUSTER_ID;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.MESSAGING_DESTINATION_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.FrozenTags;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class KafkaConsumerTagsTest {
  private static ConsumerRecord<String, String> record(String topic, long offset) {
    return new ConsumerRecord<>(topic, 0, offset, "key", "value");
  }

  @Test
  void freezesTheConsumerTagsOfATopic() {
    KafkaConsumerTags tags = new KafkaConsumerTags("group", "cluster", "broker:9092");

    TagMap frozen = tags.of(record("orders", 0)).tags();

    assertEquals(4, frozen.size());
    assertEquals("orders", frozen.getObject(MESSAGING_DESTINATION_NAME));
    assertEquals("group", frozen.getObject(CONSUMER_GROUP));
    assertEquals("cluster", frozen.getObject(KAFKA_CLUSTER_ID));
    assertEquals("broker:9092", frozen.getObject(KAFKA_BOOTSTRAP_SERVERS));
  }

  @Test
  void recordsOfTheSameTopicShareOneFreeze() {
    KafkaConsumerTags tags = new KafkaConsumerTags("group", "cluster", "broker:9092");

    FrozenTags first = tags.of(record("orders", 0));
    // an equal but distinct topic string, as a later fetch may hand out
    assertSame(first, tags.of(record(new String("orders"), 1)));
  }

  @Test
  void aNewTopicFreezesAgain() {
    KafkaConsumerTags tags = new KafkaConsumerTags("group", "cluster", "broker:9092");

    FrozenTags orders = tags.of(record("orders", 0));
    FrozenTags payments = tags.of(record("payments", 0));

    assertNotSame(orders, payments);
    assertEquals("payments", payments.tags().getObject(MESSAGING_DESTINATION_NAME));
    assertEquals(
        "orders", tags.of(record("orders", 1)).tags().getObject(MESSAGING_DESTINATION_NAME));
  }

  @Test
  void missingConsumerInfoIsLeftOut() {
    KafkaConsumerTags tags = new KafkaConsumerTags(null, null, null);

    TagMap frozen = tags.of(record("orders", 0)).tags();

    assertEquals(1, frozen.size());
    assertNull(frozen.getObject(CONSUMER_GROUP));
  }
}

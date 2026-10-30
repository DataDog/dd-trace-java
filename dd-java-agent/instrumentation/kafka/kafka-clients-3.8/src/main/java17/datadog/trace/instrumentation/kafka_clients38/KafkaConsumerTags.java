package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.CONSUMER_GROUP;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.KAFKA_CLUSTER_ID;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.MESSAGING_DESTINATION_NAME;

import datadog.trace.bootstrap.instrumentation.api.FrozenTags;
import datadog.trace.bootstrap.instrumentation.api.SpanData;
import datadog.trace.bootstrap.instrumentation.api.TagExtractor;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * The consumer tags every record of one topic in one poll shares -- destination, group, cluster id
 * and bootstrap servers -- extracted from the topic and frozen once, then applied to each record's
 * span. Records come out of a poll partition by partition, so the topic rarely changes between
 * records and one cached topic suffices. Used by one iterator, on one thread.
 */
public final class KafkaConsumerTags implements TagExtractor<String> {
  private final String group;
  private final String clusterId;
  private final String bootstrapServers;

  private String frozenTopic;
  private FrozenTags frozen;

  public KafkaConsumerTags(String group, String clusterId, String bootstrapServers) {
    this.group = group;
    this.clusterId = clusterId;
    this.bootstrapServers = bootstrapServers;
  }

  /** The frozen tags for {@code record}'s topic, frozen when the topic changes. */
  public FrozenTags of(final ConsumerRecord<?, ?> record) {
    final String topic = KafkaDecorator.topicOf(record);
    if (topic != frozenTopic && !topic.equals(frozenTopic)) {
      frozen = FrozenTags.of(topic, this);
      frozenTopic = topic;
    }
    return frozen;
  }

  @Override
  public void extract(final String topic, final SpanData span) {
    span.setTag(MESSAGING_DESTINATION_NAME, topic);
    if (group != null) {
      span.setTag(CONSUMER_GROUP, group);
    }
    if (clusterId != null) {
      span.setTag(KAFKA_CLUSTER_ID, clusterId);
    }
    if (bootstrapServers != null) {
      span.setTag(KAFKA_BOOTSTRAP_SERVERS, bootstrapServers);
    }
  }
}

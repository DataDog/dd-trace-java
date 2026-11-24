package datadog.trace.instrumentation.kafka_common;

import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * Implemented by the tracing record collections of every kafka-clients instrumentation, so that the
 * Kafka Streams instrumentation can unwrap them whatever kafka-clients version is in use.
 */
public interface TracingIterableDelegator {
  /** Used by the streams instrumentation to unwrap (disable) the iteration advice. */
  Iterable<ConsumerRecord<?, ?>> getDelegate();

  /** The id of the Kafka cluster the records were polled from, or null if not known yet. */
  String getClusterId();
}

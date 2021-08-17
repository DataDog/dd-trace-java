package datadog.trace.instrumentation.kafka_clients;

import org.apache.kafka.clients.consumer.ConsumerRecord;

public interface TracingIterableDelegator {
  // Used by the streams instrumentation to unwrap (disable) the iteration advice.
  Iterable<ConsumerRecord<?, ?>> getDelegate();
}

package datadog.trace.instrumentation.kafka_clients;

import datadog.context.propagation.CarrierSetter;
import org.apache.kafka.common.header.Headers;

public interface TextMapInjectAdapterInterface extends CarrierSetter<Headers> {
  void injectTimeInQueue(Headers headers);
}

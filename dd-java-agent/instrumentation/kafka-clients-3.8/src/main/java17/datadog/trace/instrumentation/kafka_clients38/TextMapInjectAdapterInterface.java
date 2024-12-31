package datadog.trace.instrumentation.kafka_clients38;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.apache.kafka.common.header.Headers;

public interface TextMapInjectAdapterInterface extends AgentPropagation.Setter<Headers> {
  void injectTimeInQueue(Headers headers);
}

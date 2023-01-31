package datadog.trace.bootstrap.instrumentation.api;

import java.util.function.Consumer;

public interface DataStreamsMonitoring extends Consumer<StatsPoint>, AutoCloseable {
  void start();

  PathwayContext newPathwayContext();

  <C> PathwayContext extractBinaryPathwayContext(
      C carrier, AgentPropagation.BinaryContextVisitor<C> getter);

  <C> PathwayContext extractPathwayContext(C carrier, AgentPropagation.ContextVisitor<C> getter);

  void trackKafkaProduce(String topic, int partition, long offset);

  void trackKafkaCommit(String consumerGroup, String topic, int partition, long offset);

  @Override
  void close();

  void clear();
}

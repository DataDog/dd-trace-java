package datadog.trace.api.datastreams;

import datadog.trace.api.experimental.DataStreamsCheckpointer;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Schema;
import datadog.trace.bootstrap.instrumentation.api.SchemaIterator;

public interface AgentDataStreamsMonitoring
    extends DataStreamsCheckpointer, DataStreamsTransactionTracker {
  void trackBacklog(DataStreamsTags tags, long value);

  /**
   * Sets data streams checkpoint, used for both produce and consume operations.
   *
   * @param span active span
   * @param context the data streams context
   */
  void setCheckpoint(AgentSpan span, DataStreamsContext context);

  PathwayContext newPathwayContext();

  void add(StatsPoint statsPoint);

  /**
   * trySampleSchema is used to determine if we should extract schema from the message or not.
   *
   * @param topic Kafka topic
   * @return the weight of the schema, indicating how many messages have been sent to the topic
   *     without having been sampled.
   */
  int trySampleSchema(String topic);

  boolean canSampleSchema(String topic);

  Schema getSchema(String schemaName, SchemaIterator iterator);

  void setProduceCheckpoint(String type, String target);

  /**
   * setServiceNameOverride is used override service name for all DataStreams payloads produced
   * within Thread.currentThread()
   *
   * @param serviceName new service name to use for DSM checkpoints.
   */
  void setThreadServiceName(String serviceName);

  /** clearThreadServiceName clears up service name override for Thread.currentThread() */
  void clearThreadServiceName();
}

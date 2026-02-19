package datadog.trace.api.datastreams;

import datadog.trace.api.experimental.DataStreamsCheckpointer;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Schema;
import datadog.trace.bootstrap.instrumentation.api.SchemaIterator;
import java.util.Map;

public interface AgentDataStreamsMonitoring
    extends DataStreamsCheckpointer, DataStreamsTransactionTracker {
  void trackBacklog(DataStreamsTags tags, long value);

  /**
   * Reports Kafka producer or consumer configuration for Data Streams Monitoring. Each unique
   * configuration is sent only once.
   *
   * @param type the client type, e.g. "kafka_producer" or "kafka_consumer"
   * @param kafkaClusterId the Kafka cluster identifier, or empty string if not yet known
   * @param consumerGroup the consumer group name, or empty string for producers
   * @param config the configuration key-value pairs
   */
  void reportKafkaConfig(
      String type,
      String kafkaClusterId,
      String consumerGroup,
      Map<String, String> config);

  /**
   * Tracks Schema Registry usage for Data Streams Monitoring.
   *
   * @param topic Kafka topic name
   * @param clusterId Kafka cluster ID (important: schema IDs are only unique per cluster)
   * @param schemaId Schema ID from Schema Registry
   * @param isSuccess Whether the schema operation succeeded
   * @param isKey Whether this is for the key (true) or value (false)
   * @param operation The operation type: "serialize" or "deserialize"
   */
  void reportSchemaRegistryUsage(
      String topic,
      String clusterId,
      int schemaId,
      boolean isSuccess,
      boolean isKey,
      String operation);

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

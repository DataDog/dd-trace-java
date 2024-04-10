package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.experimental.DataStreamsCheckpointer;
import java.util.LinkedHashMap;

public interface AgentDataStreamsMonitoring extends DataStreamsCheckpointer {
  void trackBacklog(LinkedHashMap<String, String> sortedTags, long value);

  /**
   * Sets data streams checkpoint, used for both produce and consume operations.
   *
   * @param span active span
   * @param sortedTags alphabetically sorted tags for the checkpoint (direction, queue type etc)
   * @param defaultTimestamp unix timestamp to use as a start of the pathway if this is the first
   *     checkpoint in the chain. Zero should be passed if we can't extract the timestamp from the
   *     message / payload itself (for instance: produce operations; http produce / consume etc).
   *     Value will be ignored for checkpoints happening not at the start of the pipeline.
   * @param payloadSizeBytes size of the message (body + headers) in bytes. Zero should be passed if
   *     the size cannot be evaluated.
   */
  void setCheckpoint(
      AgentSpan span,
      LinkedHashMap<String, String> sortedTags,
      long defaultTimestamp,
      long payloadSizeBytes);

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
}

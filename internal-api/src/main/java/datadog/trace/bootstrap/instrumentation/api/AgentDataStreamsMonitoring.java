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
   */
  void setCheckpoint(
      AgentSpan span, LinkedHashMap<String, String> sortedTags, long defaultTimestamp);
}

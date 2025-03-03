package datadog.trace.api.datastreams;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ImplicitContextKeyed;
import java.util.LinkedHashMap;

public class DataStreamsContext implements ImplicitContextKeyed {
  private static final ContextKey<DataStreamsContext> CONTEXT_KEY =
      ContextKey.named("dsm-context-key");

  final LinkedHashMap<String, String> sortedTags;
  final long defaultTimestamp;
  final long payloadSizeBytes;
  final boolean sendCheckpoint;

  public static DataStreamsContext fromContext(Context context) {
    return context.get(CONTEXT_KEY);
  }

  /**
   * Creates a DSM context.
   *
   * @param sortedTags alphabetically sorted tags for the checkpoint (direction, queue type etc)
   * @return the created context.
   */
  public static DataStreamsContext fromTags(LinkedHashMap<String, String> sortedTags) {
    return new DataStreamsContext(sortedTags, 0, 0, true);
  }

  /**
   * Creates a DSM context.
   *
   * @param sortedTags alphabetically sorted tags for the checkpoint (direction, queue type etc)
   * @param defaultTimestamp unix timestamp to use as a start of the pathway if this is the first
   *     checkpoint in the chain. Zero should be passed if we can't extract the timestamp from the
   *     message / payload itself (for instance: produce operations; http produce / consume etc).
   *     Value will be ignored for checkpoints happening not at the start of the pipeline.
   * @param payloadSizeBytes size of the message (body + headers) in bytes. Zero should be passed if
   *     the size cannot be evaluated.
   * @return the created context.
   */
  public static DataStreamsContext create(
      LinkedHashMap<String, String> sortedTags, long defaultTimestamp, long payloadSizeBytes) {
    return new DataStreamsContext(sortedTags, defaultTimestamp, payloadSizeBytes, true);
  }

  public static DataStreamsContext fromTagsWithoutCheckpoint(
      LinkedHashMap<String, String> sortedTags) {
    return new DataStreamsContext(sortedTags, 0, 0, false);
  }

  // That's basically a record for now
  private DataStreamsContext(
      LinkedHashMap<String, String> sortedTags,
      long defaultTimestamp,
      long payloadSizeBytes,
      boolean sendCheckpoint) {
    this.sortedTags = sortedTags;
    this.defaultTimestamp = defaultTimestamp;
    this.payloadSizeBytes = payloadSizeBytes;
    this.sendCheckpoint = sendCheckpoint;
  }

  public LinkedHashMap<String, String> sortedTags() {
    return this.sortedTags;
  }

  public long defaultTimestamp() {
    return this.defaultTimestamp;
  }

  public long payloadSizeBytes() {
    return this.payloadSizeBytes;
  }

  public boolean sendCheckpoint() {
    return this.sendCheckpoint;
  }

  @Override
  public Context storeInto(Context context) {
    return context.with(CONTEXT_KEY, this);
  }
}

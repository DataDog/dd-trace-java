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

  public static DataStreamsContext fromTags(LinkedHashMap<String, String> sortedTags) {
    return new DataStreamsContext(sortedTags, 0, 0, true);
  }

  public static DataStreamsContext fromKafka(
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

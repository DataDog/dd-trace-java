package datadog.trace.api.datastreams;

import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.OUTBOUND;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ImplicitContextKeyed;
import javax.annotation.Nonnull;

public class DataStreamsContext implements ImplicitContextKeyed {
  private static final ContextKey<DataStreamsContext> CONTEXT_KEY =
      ContextKey.named("dsm-context-key");
  private static final DataStreamsTags CLIENT_PATHWAY_EDGE_TAGS;
  private static final DataStreamsTags SERVER_PATHWAY_EDGE_TAGS;

  final DataStreamsTags tags;
  final long defaultTimestamp;
  final long payloadSizeBytes;
  final boolean sendCheckpoint;

  static {
    CLIENT_PATHWAY_EDGE_TAGS = DataStreamsTags.create("http", OUTBOUND);
    SERVER_PATHWAY_EDGE_TAGS = DataStreamsTags.create("http", INBOUND);
  }

  public static DataStreamsContext fromContext(Context context) {
    return context.get(CONTEXT_KEY);
  }

  /**
   * Gets default DSM context for HTTP clients.
   *
   * @return The default DSM context for HTTP clients.
   */
  public static DataStreamsContext forHttpClient() {
    return fromTags(CLIENT_PATHWAY_EDGE_TAGS);
  }

  /**
   * Gets default DSM context for HTTP servers.
   *
   * @return The default DSM context for HTTP servers.
   */
  public static DataStreamsContext forHttpServer() {
    return fromTags(SERVER_PATHWAY_EDGE_TAGS);
  }

  /**
   * Creates a DSM context.
   *
   * @param tags DataStreamsTags object
   * @return the created context.
   */
  public static DataStreamsContext fromTags(DataStreamsTags tags) {
    return new DataStreamsContext(tags, 0, 0, true);
  }

  /**
   * Creates a DSM context.
   *
   * @param tags object
   * @param defaultTimestamp unix timestamp to use as a start of the pathway if this is the first
   *     checkpoint in the chain. Zero should be passed if we can't extract the timestamp from the
   *     message / payload itself (for instance: produce operations; http produce / consume etc).
   *     Value will be ignored for checkpoints happening not at the start of the pipeline.
   * @param payloadSizeBytes size of the message (body + headers) in bytes. Zero should be passed if
   *     the size cannot be evaluated.
   * @return the created context.
   */
  public static DataStreamsContext create(
      DataStreamsTags tags, long defaultTimestamp, long payloadSizeBytes) {
    return new DataStreamsContext(tags, defaultTimestamp, payloadSizeBytes, true);
  }

  public static DataStreamsContext fromTagsWithoutCheckpoint(DataStreamsTags tags) {
    return new DataStreamsContext(tags, 0, 0, false);
  }

  // That's basically a record for now
  private DataStreamsContext(
      DataStreamsTags tags, long defaultTimestamp, long payloadSizeBytes, boolean sendCheckpoint) {
    this.tags = tags;
    this.defaultTimestamp = defaultTimestamp;
    this.payloadSizeBytes = payloadSizeBytes;
    this.sendCheckpoint = sendCheckpoint;
  }

  public DataStreamsTags tags() {
    return this.tags;
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
  public Context storeInto(@Nonnull Context context) {
    return context.with(CONTEXT_KEY, this);
  }
}

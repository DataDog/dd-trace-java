package datadog.trace.core.datastreams;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.Writable;
import datadog.communication.serialization.WritableFormatter;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.api.Config;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.datastreams.TransactionInfo;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.common.metrics.Sink;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class MsgPackDatastreamsPayloadWriter implements DatastreamsPayloadWriter {
  private static final byte[] ENV = "Env".getBytes(ISO_8859_1);
  private static final byte[] VERSION = "Version".getBytes(ISO_8859_1);
  private static final byte[] PRIMARY_TAG = "PrimaryTag".getBytes(ISO_8859_1);
  private static final byte[] LANG = "Lang".getBytes(ISO_8859_1);
  private static final byte[] TRACER_VERSION = "TracerVersion".getBytes(ISO_8859_1);
  private static final byte[] STATS = "Stats".getBytes(ISO_8859_1);
  private static final byte[] START = "Start".getBytes(ISO_8859_1);
  private static final byte[] DURATION = "Duration".getBytes(ISO_8859_1);
  private static final byte[] PATHWAY_LATENCY = "PathwayLatency".getBytes(ISO_8859_1);
  private static final byte[] EDGE_LATENCY = "EdgeLatency".getBytes(ISO_8859_1);
  private static final byte[] PAYLOAD_SIZE = "PayloadSize".getBytes(ISO_8859_1);
  private static final byte[] SERVICE = "Service".getBytes(ISO_8859_1);
  private static final byte[] EDGE_TAGS = "EdgeTags".getBytes(ISO_8859_1);
  private static final byte[] BACKLOGS = "Backlogs".getBytes(ISO_8859_1);
  private static final byte[] HASH = "Hash".getBytes(ISO_8859_1);
  private static final byte[] PARENT_HASH = "ParentHash".getBytes(ISO_8859_1);
  private static final byte[] BACKLOG_VALUE = "Value".getBytes(ISO_8859_1);
  private static final byte[] BACKLOG_TAGS = "Tags".getBytes(ISO_8859_1);
  private static final byte[] PRODUCTS_MASK = "ProductMask".getBytes(ISO_8859_1);
  private static final byte[] PROCESS_TAGS = "ProcessTags".getBytes(ISO_8859_1);
  private static final byte[] TRANSACTIONS = "Transactions".getBytes(ISO_8859_1);
  private static final byte[] TRANSACTION_CHECKPOINT_IDS =
      "TransactionCheckpointIds".getBytes(ISO_8859_1);

  private static final int INITIAL_CAPACITY = 512 * 1024;

  private final WritableFormatter writer;
  private final Sink sink;
  private final GrowableBuffer buffer;
  private final WellKnownTags wellKnownTags;
  private final byte[] tracerVersionValue;
  private final byte[] primaryTagValue;

  public MsgPackDatastreamsPayloadWriter(
      Sink sink, WellKnownTags wellKnownTags, String tracerVersion, String primaryTag) {
    buffer = new GrowableBuffer(INITIAL_CAPACITY);
    writer = new MsgPackWriter(buffer);
    this.sink = sink;
    this.wellKnownTags = wellKnownTags;
    tracerVersionValue = tracerVersion.getBytes(ISO_8859_1);
    primaryTagValue = primaryTag == null ? new byte[0] : primaryTag.getBytes(ISO_8859_1);
  }

  public void reset() {
    buffer.reset();
  }

  // extend the list as needed
  private static final int APM_PRODUCT = 1; // 00000001
  private static final int DSM_PRODUCT = 2; // 00000010
  private static final int DJM_PRODUCT = 4; // 00000100
  private static final int PROFILING_PRODUCT = 8; // 00001000

  public long getProductsMask() {
    long productsMask = APM_PRODUCT;
    if (Config.get().isDataStreamsEnabled()) {
      productsMask |= DSM_PRODUCT;
    }
    if (Config.get().isDataJobsEnabled()) {
      productsMask |= DJM_PRODUCT;
    }
    if (Config.get().isProfilingEnabled()) {
      productsMask |= PROFILING_PRODUCT;
    }

    return productsMask;
  }

  @Override
  public void writePayload(Collection<StatsBucket> data, String serviceNameOverride) {
    final List<UTF8BytesString> processTags = ProcessTags.getTagsAsUTF8ByteStringList();
    final boolean hasProcessTags = processTags != null;
    writer.startMap(8 + (hasProcessTags ? 1 : 0));
    /* 1 */
    writer.writeUTF8(ENV);
    writer.writeUTF8(wellKnownTags.getEnv());

    /* 2 */
    writer.writeUTF8(SERVICE);
    if (serviceNameOverride != null && !serviceNameOverride.isEmpty()) {
      System.out.println("### Service name from override: " + serviceNameOverride);
      writer.writeUTF8(serviceNameOverride.getBytes(ISO_8859_1));
    } else {
      System.out.println(
          "### Well known tags: " + wellKnownTags + ", service " + wellKnownTags.getService());
      writer.writeUTF8(wellKnownTags.getService());
    }

    /* 3 */
    writer.writeUTF8(LANG);
    writer.writeUTF8(wellKnownTags.getLanguage());

    /* 4 */
    writer.writeUTF8(PRIMARY_TAG);
    writer.writeUTF8(primaryTagValue);

    /* 5 */
    writer.writeUTF8(TRACER_VERSION);
    writer.writeUTF8(tracerVersionValue);

    /* 6 */
    writer.writeUTF8(VERSION);
    writer.writeUTF8(wellKnownTags.getVersion());

    /* 7 */
    writer.writeUTF8(STATS);
    writer.startArray(data.size());

    for (StatsBucket bucket : data) {
      boolean hasBacklogs = !bucket.getBacklogs().isEmpty();
      boolean hasTransactions = !bucket.getTransactions().isEmpty();
      writer.startMap(3 + (hasBacklogs ? 1 : 0) + (hasTransactions ? 2 : 0));

      /* 1 */
      writer.writeUTF8(START);
      writer.writeLong(bucket.getStartTimeNanos());

      /* 2 */
      writer.writeUTF8(DURATION);
      writer.writeLong(bucket.getBucketDurationNanos());

      /* 3 */
      writer.writeUTF8(STATS);
      writeBucket(bucket, writer);

      if (hasBacklogs) {
        /* 4 */
        writeBacklogs(bucket.getBacklogs(), writer);
      }

      if (hasTransactions) {
        /* 5 */
        writer.writeUTF8(TRANSACTIONS);
        writer.writeBinary(bucket.getTransactions().getData());
        writer.writeUTF8(TRANSACTION_CHECKPOINT_IDS);
        writer.writeBinary(TransactionInfo.getCheckpointIdCacheBytes());
      }
    }

    /* 8 */
    writer.writeUTF8(PRODUCTS_MASK);
    writer.writeLong(getProductsMask());

    /* 9 */
    if (hasProcessTags) {
      writer.writeUTF8(PROCESS_TAGS);
      writer.startArray(processTags.size());
      processTags.forEach(writer::writeUTF8);
    }

    buffer.mark();
    sink.accept(buffer.messageCount(), buffer.slice());
    buffer.reset();
  }

  private void writeBucket(StatsBucket bucket, Writable packer) {
    Collection<StatsGroup> groups = bucket.getGroups();
    packer.startArray(groups.size());
    for (StatsGroup group : groups) {
      boolean firstNode = group.getTags().nonNullSize() == 0;
      packer.startMap(firstNode ? 5 : 6);

      /* 1 */
      packer.writeUTF8(PATHWAY_LATENCY);
      packer.writeBinary(group.getPathwayLatency().serialize());

      /* 2 */
      packer.writeUTF8(EDGE_LATENCY);
      packer.writeBinary(group.getEdgeLatency().serialize());

      /* 3 */
      packer.writeUTF8(PAYLOAD_SIZE);
      packer.writeBinary(group.getPayloadSize().serialize());

      /* 4 */
      packer.writeUTF8(HASH);
      packer.writeUnsignedLong(group.getHash());

      /* 5 */
      packer.writeUTF8(PARENT_HASH);
      packer.writeUnsignedLong(group.getParentHash());

      if (!firstNode) {
        /* 6 */
        packer.writeUTF8(EDGE_TAGS);
        writeDataStreamsTags(group.getTags(), packer);
      }
    }
  }

  private void writeBacklogs(
      Collection<Map.Entry<DataStreamsTags, Long>> backlogs, Writable packer) {
    packer.writeUTF8(BACKLOGS);
    packer.startArray(backlogs.size());
    for (Map.Entry<DataStreamsTags, Long> entry : backlogs) {
      packer.startMap(2);

      packer.writeUTF8(BACKLOG_TAGS);
      writeDataStreamsTags(entry.getKey(), packer);

      packer.writeUTF8(BACKLOG_VALUE);
      packer.writeLong(entry.getValue());
    }
  }

  private void writeDataStreamsTags(DataStreamsTags tags, Writable packer) {
    packer.startArray(tags.nonNullSize());

    for (int i = 0; i < tags.size(); i++) {
      String val = tags.tagByIndex(i);
      if (val != null) {
        packer.writeString(val, null);
      }
    }
  }
}

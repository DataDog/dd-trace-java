package datadog.trace.core.datastreams;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.Writable;
import datadog.communication.serialization.WritableFormatter;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.api.WellKnownTags;
import datadog.trace.common.metrics.Sink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

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
  private static final byte[] TRANSACTION_IDENTIFIER = "Transaction".getBytes(ISO_8859_1);

  private static final Logger log = LoggerFactory.getLogger(MsgPackDatastreamsPayloadWriter.class);

  private static final int INITIAL_CAPACITY = 512 * 1024;

  private final WritableFormatter writer;
  private final Sink sink;
  private final GrowableBuffer buffer;
  private final WellKnownTags wellKnownTags;
  private final byte[] tracerVersionValue;
  private final byte[] primaryTagValue;

  public static class TransactionPayload {
    private final String transactionId;
    private final long pathwayHash;

    public TransactionPayload(String transactionId, long pathwayHash) {
      this.transactionId = transactionId;
      this.pathwayHash = pathwayHash;
    }

    public String getTransactionId() {
      return transactionId;
    }

    public long getPathwayHash() {
      return pathwayHash;
    }
  }

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

  @Override
  public void writePayload(Collection<StatsBucket> data) {
    writer.startMap(7);
    /* 1 */
    writer.writeUTF8(ENV);
    writer.writeUTF8(wellKnownTags.getEnv());

    /* 2 */
    writer.writeUTF8(SERVICE);
    writer.writeUTF8(wellKnownTags.getService());

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
      writer.startMap(3 + (hasBacklogs ? 1 : 0));

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
    }

    buffer.mark();
    sink.accept(buffer.messageCount(), buffer.slice());
    buffer.reset();
  }

  @Override
  public void writeCompressedTransactionPayload(List<TransactionPayload> payloads) {
    if (payloads.isEmpty()) {
      log.warn("No transaction payloads to write.");
      return;
    }

    try {
      GrowableBuffer serializeBuffer = new GrowableBuffer(INITIAL_CAPACITY);
      MsgPackWriter msgPackWriter = new MsgPackWriter(serializeBuffer);

      msgPackWriter.startArray(payloads.size());
      for (TransactionPayload payload : payloads) {
        msgPackWriter.startMap(2); // since each transaction has two fields
        msgPackWriter.writeUTF8("TransactionId".getBytes(ISO_8859_1));
        msgPackWriter.writeUTF8(payload.getTransactionId().getBytes(ISO_8859_1));
        msgPackWriter.writeUTF8("PathwayHash".getBytes(ISO_8859_1));
        msgPackWriter.writeLong(payload.getPathwayHash());
      }
      msgPackWriter.flush();

      // Capture the slice once and reuse it
      ByteBuffer slice = serializeBuffer.slice();
      byte[] serializedData = new byte[slice.remaining()];
      slice.get(serializedData);

      // Compress the serialized data
      ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
      try (GZIPOutputStream gzipOS = new GZIPOutputStream(byteStream)) {
        gzipOS.write(serializedData);
      }
      byte[] compressedData = byteStream.toByteArray();

      // Prepare final buffer with compressed data
      GrowableBuffer finalBuffer = new GrowableBuffer(INITIAL_CAPACITY);
      MsgPackWriter finalWriter = new MsgPackWriter(finalBuffer);
      finalWriter.startMap(1); // Single key-value pair
      finalWriter.writeUTF8("CompressedTransactions".getBytes(ISO_8859_1));
      finalWriter.writeBinary(compressedData);
      finalWriter.flush();

      sink.accept(finalBuffer.messageCount(), finalBuffer.slice());
      finalBuffer.reset();
      log.info("Successfully wrote compressed transaction payload");

    } catch (IOException e) {
      log.error("Failed to compress and write transaction payloads", e);
    } catch (Exception e) {
      log.error("Unexpected error in writeCompressedTransactionPayload", e);
    }
  }


  public void writeTransactionPayload(TransactionPayload transaction) {
    writer.startMap(2);

    writer.writeUTF8("Type".getBytes(ISO_8859_1));
    writer.writeUTF8(TRANSACTION_IDENTIFIER);

    writer.writeUTF8("TransactionData".getBytes(ISO_8859_1));
    writer.startMap(2);

    writer.writeUTF8("TransactionId".getBytes(ISO_8859_1));
    writer.writeUTF8(transaction.getTransactionId().getBytes(ISO_8859_1));

    writer.writeUTF8("PathwayHash".getBytes(ISO_8859_1));
    writer.writeLong(transaction.getPathwayHash());

    buffer.mark();
    sink.accept(buffer.messageCount(), buffer.slice());
    buffer.reset();
    log.info("Successfully sent transaction payload");
  }
  private void writeBucket(StatsBucket bucket, Writable packer) {
    Collection<StatsGroup> groups = bucket.getGroups();
    packer.startArray(groups.size());
    for (StatsGroup group : groups) {
      boolean firstNode = group.getEdgeTags().isEmpty();

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
        packer.startArray(group.getEdgeTags().size());
        for (String tag : group.getEdgeTags()) {
          packer.writeString(tag, null);
        }
      }
    }
  }

  private void writeBacklogs(Collection<Map.Entry<List<String>, Long>> backlogs, Writable packer) {
    packer.writeUTF8(BACKLOGS);
    packer.startArray(backlogs.size());
    for (Map.Entry<List<String>, Long> entry : backlogs) {
      packer.startMap(2);
      packer.writeUTF8(BACKLOG_TAGS);
      packer.startArray(entry.getKey().size());
      for (String tag : entry.getKey()) {
        packer.writeString(tag, null);
      }
      packer.writeUTF8(BACKLOG_VALUE);
      packer.writeLong(entry.getValue());
    }
  }
}

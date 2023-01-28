package datadog.trace.core.datastreams;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.Writable;
import datadog.communication.serialization.WritableFormatter;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.api.WellKnownTags;
import datadog.trace.common.metrics.Sink;
import java.util.Collection;
import java.util.Map;

public class MsgPackDatastreamsPayloadWriter implements DatastreamsPayloadWriter {
  private static final byte[] ENV = "Env".getBytes(ISO_8859_1);
  private static final byte[] PRIMARY_TAG = "PrimaryTag".getBytes(ISO_8859_1);
  private static final byte[] LANG = "Lang".getBytes(ISO_8859_1);
  private static final byte[] TRACER_VERSION = "TracerVersion".getBytes(ISO_8859_1);
  private static final byte[] STATS = "Stats".getBytes(ISO_8859_1);
  private static final byte[] START = "Start".getBytes(ISO_8859_1);
  private static final byte[] DURATION = "Duration".getBytes(ISO_8859_1);
  private static final byte[] PATHWAY_LATENCY = "PathwayLatency".getBytes(ISO_8859_1);
  private static final byte[] EDGE_LATENCY = "EdgeLatency".getBytes(ISO_8859_1);
  private static final byte[] SERVICE = "Service".getBytes(ISO_8859_1);
  private static final byte[] EDGE_TAGS = "EdgeTags".getBytes(ISO_8859_1);
  private static final byte[] KAFKA = "Kafka".getBytes(ISO_8859_1);
  private static final byte[] LATEST_PRODUCE_OFFSETS = "LatestProduceOffsets".getBytes(ISO_8859_1);
  private static final byte[] LATEST_COMMIT_OFFSETS = "LatestCommitOffsets".getBytes(ISO_8859_1);
  private static final byte[] HASH = "Hash".getBytes(ISO_8859_1);
  private static final byte[] PARENT_HASH = "ParentHash".getBytes(ISO_8859_1);
  private static final byte[] TOPIC = "Topic".getBytes(ISO_8859_1);
  private static final byte[] CONSUMER_GROUP = "ConsumerGroup".getBytes(ISO_8859_1);
  private static final byte[] PARTITION = "Partition".getBytes(ISO_8859_1);
  private static final byte[] OFFSET = "Offset".getBytes(ISO_8859_1);

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

  @Override
  public void writePayload(Collection<StatsBucket> data) {
    writer.startMap(6);
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
    writer.writeUTF8(STATS);
    writer.startArray(data.size());
    for (StatsBucket bucket : data) {
      boolean hasKafka =
          !bucket.getLatestKafkaCommitOffsets().isEmpty()
              || !bucket.getLatestKafkaProduceOffsets().isEmpty();
      writer.startMap(3 + (hasKafka ? 1 : 0));

      /* 1 */
      writer.writeUTF8(START);
      writer.writeLong(bucket.getStartTimeNanos());

      /* 2 */
      writer.writeUTF8(DURATION);
      writer.writeLong(bucket.getBucketDurationNanos());

      /* 3 */
      writer.writeUTF8(STATS);
      writeBucket(bucket, writer);

      if (hasKafka) {
        /* 4 */
        writeKafka(
            bucket.getLatestKafkaCommitOffsets(), bucket.getLatestKafkaProduceOffsets(), writer);
      }
    }

    buffer.mark();
    sink.accept(buffer.messageCount(), buffer.slice());
    buffer.reset();
  }

  private void writeBucket(StatsBucket bucket, Writable packer) {
    System.out.println("printing bucket");
    Collection<StatsGroup> groups = bucket.getGroups();
    packer.startArray(groups.size());
    for (StatsGroup group : groups) {
      boolean firstNode = group.getEdgeTags().isEmpty();

      packer.startMap(firstNode ? 4 : 5);

      /* 1 */
      packer.writeUTF8(PATHWAY_LATENCY);
      packer.writeBinary(group.getPathwayLatency().serialize());

      /* 2 */
      packer.writeUTF8(EDGE_LATENCY);
      packer.writeBinary(group.getEdgeLatency().serialize());

      /* 3 */
      packer.writeUTF8(HASH);
      packer.writeUnsignedLong(group.getHash());

      /* 4 */
      packer.writeUTF8(PARENT_HASH);
      packer.writeUnsignedLong(group.getParentHash());

      if (!firstNode) {
        /* 5 */
        packer.writeUTF8(EDGE_TAGS);
        packer.startArray(group.getEdgeTags().size());
        for (String tag : group.getEdgeTags()) {
          packer.writeString(tag, null);
        }
      }
    }
  }

  private void writeKafka(
      Collection<Map.Entry<TopicPartitionGroup, Long>> latestCommitOffsets,
      Collection<Map.Entry<TopicPartition, Long>> latestProduceOffsets,
      Writable packer) {
    System.out.println("has kafka!");
    packer.writeUTF8(KAFKA);
    boolean hasCommitOffsets = !latestCommitOffsets.isEmpty();
    boolean hasProduceOffsets = !latestProduceOffsets.isEmpty();
    packer.startMap((hasCommitOffsets ? 1 : 0) + (hasProduceOffsets ? 1 : 0));
    if (hasCommitOffsets) {
      System.out.println("has commit");
      packer.writeUTF8(LATEST_COMMIT_OFFSETS);
      packer.startArray(latestCommitOffsets.size());
      for (Map.Entry<TopicPartitionGroup, Long> entry : latestCommitOffsets) {
        packer.startMap(4);
        packer.writeUTF8(CONSUMER_GROUP);
        packer.writeString(entry.getKey().getGroup(), null);
        packer.writeUTF8(TOPIC);
        packer.writeString(entry.getKey().getTopic(), null);
        packer.writeUTF8(PARTITION);
        packer.writeInt(entry.getKey().getPartition());
        packer.writeUTF8(OFFSET);
        packer.writeLong(entry.getValue());
      }
    }
    if (hasProduceOffsets) {
      System.out.println("has produce");
      packer.writeUTF8(LATEST_PRODUCE_OFFSETS);
      packer.startArray(latestProduceOffsets.size());
      for (Map.Entry<TopicPartition, Long> entry : latestProduceOffsets) {
        packer.startMap(3);
        packer.writeUTF8(TOPIC);
        packer.writeString(entry.getKey().getTopic(), null);
        packer.writeUTF8(PARTITION);
        packer.writeInt(entry.getKey().getPartition());
        packer.writeUTF8(OFFSET);
        packer.writeLong(entry.getValue());
      }
    }
  }
}

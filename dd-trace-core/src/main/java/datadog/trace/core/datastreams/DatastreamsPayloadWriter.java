package datadog.trace.core.datastreams;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.Writable;
import datadog.communication.serialization.WritableFormatter;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.common.metrics.Sink;
import java.util.Collection;

public class DatastreamsPayloadWriter {
  private static final byte[] ENV = "Env".getBytes(ISO_8859_1);
  private static final byte[] STATS = "Stats".getBytes(ISO_8859_1);
  private static final byte[] START = "Start".getBytes(ISO_8859_1);
  private static final byte[] DURATION = "Duration".getBytes(ISO_8859_1);
  private static final byte[] PATHWAY_LATENCY = "PathwayLatency".getBytes(ISO_8859_1);
  private static final byte[] EDGE_LATENCY = "EdgeLatency".getBytes(ISO_8859_1);
  private static final byte[] SERVICE = "Service".getBytes(ISO_8859_1);
  private static final byte[] EDGE = "Service".getBytes(ISO_8859_1);
  private static final byte[] HASH = "Hash".getBytes(ISO_8859_1);
  private static final byte[] PARENT_HASH = "ParentHash".getBytes(ISO_8859_1);

  private static final int INITIAL_CAPACITY = 512 * 1024;

  private final WritableFormatter writer;
  private final Sink sink;
  private final GrowableBuffer buffer;
  private final byte[] envValue;

  public DatastreamsPayloadWriter(Sink sink, String env) {
    buffer = new GrowableBuffer(INITIAL_CAPACITY);
    writer = new MsgPackWriter(buffer);
    this.sink = sink;
    this.envValue = env.getBytes(ISO_8859_1);
  }

  public void reset() {
    buffer.reset();
  }

  public void writePayload(Collection<StatsBucket> data) {
    writer.startMap(2);
    /* 1 */
    writer.writeUTF8(ENV);
    writer.writeUTF8(envValue);

    /* 2 */
    writer.writeUTF8(STATS);
    writer.startArray(data.size());
    for (StatsBucket bucket : data) {
      writer.startMap(3);

      /* 1 */
      writer.writeUTF8(START);
      writer.writeLong(bucket.getStartTime());

      /* 2 */
      writer.writeUTF8(DURATION);
      writer.writeLong(bucket.getBucketDurationNanos());

      /* 3 */
      writer.writeUTF8(STATS);
      writeBucket(bucket, writer);
    }

    buffer.mark();
    sink.accept(buffer.messageCount(), buffer.slice());
    buffer.reset();
  }

  private void writeBucket(StatsBucket bucket, Writable packer) {
    Collection<StatsGroup> groups = bucket.getGroups();
    packer.startArray(groups.size());
    for (StatsGroup group : groups) {
      packer.startMap(6);

      /* 1 */
      packer.writeUTF8(PATHWAY_LATENCY);
      packer.writeBinary(group.getPathwayLatency().serialize());

      /* 2 */
      packer.writeUTF8(EDGE_LATENCY);
      packer.writeBinary(group.getEdgeLatency().serialize());

      /* 3 */
      packer.writeUTF8(SERVICE);
      packer.writeString(group.getService(), null);

      /* 3 */
      packer.writeUTF8(EDGE);
      packer.writeString(group.getEdge(), null);

      /* 3 */
      packer.writeUTF8(HASH);
      packer.writeLong(group.getHash());

      /* 3 */
      packer.writeUTF8(PARENT_HASH);
      packer.writeLong(group.getParentHash());
    }
  }
}

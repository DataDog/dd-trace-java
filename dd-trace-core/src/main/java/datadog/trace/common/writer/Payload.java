package datadog.trace.common.writer;

import static datadog.communication.serialization.msgpack.MsgPackWriter.ARRAY16;
import static datadog.communication.serialization.msgpack.MsgPackWriter.ARRAY32;
import static datadog.communication.serialization.msgpack.MsgPackWriter.FIXARRAY;
import static datadog.communication.serialization.msgpack.MsgPackWriter.FIXMAP;
import static datadog.communication.serialization.msgpack.MsgPackWriter.MAP16;
import static datadog.communication.serialization.msgpack.MsgPackWriter.MAP32;
import static java.util.Collections.singletonList;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;
import okhttp3.RequestBody;

public abstract class Payload {

  private static final ByteBuffer EMPTY_ARRAY = ByteBuffer.allocate(1).put(0, (byte) 0x90);

  private int traceCount = 0;
  private long droppedTraces = 0;
  private long droppedSpans = 0;
  private List<ByteBuffer> body = singletonList(EMPTY_ARRAY);

  public Payload withBody(int traceCount, ByteBuffer body) {
    return withBody(traceCount, null == body ? null : singletonList(body));
  }

  /** Sets a body held in parts, such as the chunks of a chunked buffer, in order. */
  public Payload withBody(int traceCount, List<ByteBuffer> body) {
    this.traceCount = traceCount;
    if (null != body) {
      this.body = body;
    }
    return this;
  }

  public Payload withDroppedTraces(long droppedTraceCount) {
    this.droppedTraces += droppedTraceCount;
    return this;
  }

  public Payload withDroppedSpans(long droppedSpanCount) {
    this.droppedSpans += droppedSpanCount;
    return this;
  }

  public int traceCount() {
    return traceCount;
  }

  public long droppedTraces() {
    return droppedTraces;
  }

  public long droppedSpans() {
    return droppedSpans;
  }

  public abstract int sizeInBytes();

  public abstract void writeTo(WritableByteChannel channel) throws IOException;

  public abstract RequestBody toRequest();

  /** The size of the body in bytes. */
  protected int bodySize() {
    int size = 0;
    for (ByteBuffer part : body) {
      size += part.remaining();
    }
    return size;
  }

  /**
   * Writes the body to the channel. The body isn't consumed, so it can be written again, as when a
   * request is retried.
   */
  protected void writeBody(WritableByteChannel channel) throws IOException {
    for (ByteBuffer part : body) {
      writeFully(part.duplicate(), channel);
    }
  }

  /** Writes all of the buffer's remaining bytes to the channel. */
  protected static void writeFully(ByteBuffer buffer, WritableByteChannel channel)
      throws IOException {
    while (buffer.hasRemaining()) {
      channel.write(buffer);
    }
  }

  /**
   * The given buffers followed by new views of the body, for building a request. A {@code null}
   * prefix buffer is skipped.
   */
  protected List<ByteBuffer> bodyAfter(ByteBuffer... prefix) {
    List<ByteBuffer> buffers = new ArrayList<>(prefix.length + body.size());
    for (ByteBuffer buffer : prefix) {
      if (null != buffer) {
        buffers.add(buffer);
      }
    }
    for (ByteBuffer part : body) {
      buffers.add(part.duplicate());
    }
    return buffers;
  }

  protected int msgpackArrayHeaderSize(int count) {
    if (count < 0x10) {
      return 1;
    } else if (count < 0x10000) {
      return 3;
    } else {
      return 5;
    }
  }

  protected int msgpackMapHeaderSize(int count) {
    if (count < 0x10) {
      return 1;
    } else if (count < 0x10000) {
      return 3;
    } else {
      return 5;
    }
  }

  protected ByteBuffer msgpackArrayHeader(int count) {
    if (count < 0x10) {
      return ByteBuffer.allocate(1).put(0, (byte) (FIXARRAY | count));
    } else if (count < 0x10000) {
      return ByteBuffer.allocate(3).put(0, ARRAY16).putShort(1, (short) count);
    } else {
      return ByteBuffer.allocate(5).put(0, ARRAY32).putInt(1, count);
    }
  }

  protected ByteBuffer msgpackMapHeader(int count) {
    if (count < 0x10) {
      return ByteBuffer.allocate(1).put(0, (byte) (FIXMAP | count));
    } else if (count < 0x10000) {
      return ByteBuffer.allocate(3).put(0, MAP16).putShort((short) count);
    } else {
      return ByteBuffer.allocate(5).put(0, MAP32).putInt(1, count);
    }
  }
}

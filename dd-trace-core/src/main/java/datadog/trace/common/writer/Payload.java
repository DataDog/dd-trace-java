package datadog.trace.common.writer;

import static datadog.communication.serialization.msgpack.MsgPackWriter.ARRAY16;
import static datadog.communication.serialization.msgpack.MsgPackWriter.ARRAY32;
import static datadog.communication.serialization.msgpack.MsgPackWriter.FIXARRAY;
import static datadog.communication.serialization.msgpack.MsgPackWriter.FIXMAP;
import static datadog.communication.serialization.msgpack.MsgPackWriter.MAP16;
import static datadog.communication.serialization.msgpack.MsgPackWriter.MAP32;

import datadog.http.client.HttpRequestBody;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

public abstract class Payload {

  private static final ByteBuffer EMPTY_ARRAY = ByteBuffer.allocate(1).put(0, (byte) 0x90);

  private int traceCount = 0;
  private long droppedTraces = 0;
  private long droppedSpans = 0;
  protected ByteBuffer body = EMPTY_ARRAY.duplicate();

  public Payload withBody(int traceCount, ByteBuffer body) {
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

  public abstract HttpRequestBody toRequest();

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

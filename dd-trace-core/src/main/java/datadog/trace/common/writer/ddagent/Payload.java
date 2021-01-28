package datadog.trace.common.writer.ddagent;

import static datadog.trace.core.serialization.msgpack.MsgPackWriter.ARRAY16;
import static datadog.trace.core.serialization.msgpack.MsgPackWriter.ARRAY32;
import static datadog.trace.core.serialization.msgpack.MsgPackWriter.FIXARRAY;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import okhttp3.RequestBody;

public abstract class Payload {

  private static final ByteBuffer EMPTY_ARRAY = ByteBuffer.allocate(1).put(0, (byte) 0x90);

  private int traceCount = 0;
  protected ByteBuffer body = EMPTY_ARRAY.duplicate();

  public Payload withBody(int traceCount, ByteBuffer body) {
    this.traceCount = traceCount;
    if (null != body) {
      this.body = body;
    }
    return this;
  }

  int traceCount() {
    return traceCount;
  }

  abstract int sizeInBytes();

  abstract void writeTo(WritableByteChannel channel) throws IOException;

  abstract RequestBody toRequest();

  protected int msgpackArrayHeaderSize(int count) {
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
}

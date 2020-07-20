package datadog.trace.common.writer.ddagent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

public abstract class Payload {

  private int representativeCount = 0;
  private int traceCount = 0;
  protected ByteBuffer body;

  public Payload withRepresentativeCount(int representativeCount) {
    this.representativeCount = representativeCount;
    return this;
  }

  public Payload withBody(int traceCount, ByteBuffer body) {
    this.traceCount = traceCount;
    this.body = body;
    return this;
  }

  int traceCount() {
    return traceCount;
  }

  int representativeCount() {
    return representativeCount;
  }

  abstract int sizeInBytes();

  abstract void writeTo(WritableByteChannel channel) throws IOException;

  protected static int sizeInBytes(ByteBuffer buffer) {
    return null == buffer ? 0 : buffer.limit() - buffer.position();
  }

  protected static void writeBufferToChannel(ByteBuffer buffer, WritableByteChannel channel)
      throws IOException {
    if (null != buffer) {
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
    }
  }
}

package datadog.trace.common.writer.ddagent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import okhttp3.RequestBody;

public abstract class Payload {

  private int traceCount = 0;
  protected ByteBuffer body;

  public Payload withBody(int traceCount, ByteBuffer body) {
    this.traceCount = traceCount;
    this.body = body;
    return this;
  }

  int traceCount() {
    return traceCount;
  }

  abstract int sizeInBytes();

  abstract void writeTo(WritableByteChannel channel) throws IOException;

  abstract RequestBody toRequest();
}

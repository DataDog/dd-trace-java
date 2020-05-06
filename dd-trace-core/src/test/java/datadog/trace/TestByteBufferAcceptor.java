package datadog.trace;

import datadog.trace.common.writer.ddagent.DispatchingMessageBufferOutput;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class TestByteBufferAcceptor implements DispatchingMessageBufferOutput.Output {

  private final boolean stripPrefix;
  private byte[] data;

  public TestByteBufferAcceptor(boolean stripPrefix) {
    this.stripPrefix = stripPrefix;
  }

  @Override
  public void accept(int traceCount, int representativeCount, ByteBuffer buffer) {
    byte[] data = buffer.array();
    if (stripPrefix) {
      this.data = Arrays.copyOfRange(data, 5, buffer.limit());
    } else {
      this.data = Arrays.copyOf(data, buffer.limit());
    }
  }

  public byte[] getData() {
    return data;
  }
}

package datadog.trace.common.writer.ddagent;

import org.msgpack.core.buffer.MessageBuffer;
import org.msgpack.core.buffer.MessageBufferOutput;

import java.io.IOException;
import java.nio.ByteBuffer;

public class ByteBufferMessageBufferOutput implements MessageBufferOutput {

  private final ByteBuffer buffer;
  private final MessageBuffer messageBuffer;
  private final DDAgentApi api;

  private int traceCount;

  public ByteBufferMessageBufferOutput(int bufferSize, DDAgentApi api) {
    this.buffer = ByteBuffer.allocate(bufferSize);
    // messageBuffer is a view on the buffer
    this.messageBuffer = MessageBuffer.wrap(buffer);
    this.api = api;
  }

  public ByteBufferMessageBufferOutput(DDAgentApi api) {
    this(10 << 20, api);
  }


  @Override
  public MessageBuffer next(int minimumSize) {
    if (minimumSize >= buffer.capacity()) {
      throw new IllegalStateException("undersized data buffer need " + minimumSize);
    }
    return messageBuffer;
  }

  @Override
  public void writeBuffer(int length) throws IOException {

  }

  @Override
  public void write(byte[] buffer, int offset, int length) throws IOException {

  }

  @Override
  public void add(byte[] buffer, int offset, int length) throws IOException {

  }

  @Override
  public void close() throws IOException {

  }

  @Override
  public void flush() throws IOException {

  }
}

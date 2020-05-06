package datadog.trace.common.writer.ddagent;

import static org.msgpack.core.MessagePack.Code.ARRAY16;
import static org.msgpack.core.MessagePack.Code.ARRAY32;
import static org.msgpack.core.MessagePack.Code.FIXARRAY_PREFIX;

import org.msgpack.core.buffer.MessageBuffer;
import org.msgpack.core.buffer.MessageBufferOutput;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

public class DispatchingMessageBufferOutput implements MessageBufferOutput {

  public interface Output {
    void accept(int traceCount, int representativeCount, ByteBuffer buffer);
  }

  // TODO pad this?
  private final AtomicInteger dropped = new AtomicInteger(0);

  private final ByteBuffer buffer;
  private final Output output;

  private MessageBuffer messageBuffer;
  private int traceCount;

  public DispatchingMessageBufferOutput(int bufferSize, Output output) {
    this.buffer = ByteBuffer.allocate(bufferSize);
    this.output = output;
  }

  public DispatchingMessageBufferOutput(Output output) {
    this(10 << 20, output);
  }

  public void onTraceWritten() {
    ++traceCount;
  }

  public void onDroppedTrace(int howMany) {
    dropped.addAndGet(howMany);
  }


  @Override
  public MessageBuffer next(int minimumSize) {
    if (null == messageBuffer) {
      messageBuffer = newMessageBuffer();
    }
    return messageBuffer;
  }

  @Override
  public void writeBuffer(int length) {
    flushInternal(length);
  }

  @Override
  public void write(byte[] buffer, int offset, int length) {
    assert false : "unexpected call: write byte[]";
  }

  @Override
  public void add(byte[] buffer, int offset, int length) {
    assert false : "unexpected call: add byte[]";
  }

  @Override
  public void close() {
  }

  @Override
  public void flush() {
    // can't safely do anything, only the MessagePacker knows how much of the ByteBuffer has been written to
  }

  private void flushInternal(int length) {
    ByteBuffer buffer = prependTraceCount();
    output.accept(traceCount, traceCount + dropped.getAndSet(0),
      (ByteBuffer)buffer.limit(length + 5));
    buffer.limit(buffer.capacity());
    traceCount = 0;
    messageBuffer = newMessageBuffer();
  }

  private MessageBuffer newMessageBuffer() {
    return MessageBuffer.wrap((ByteBuffer)(buffer.slice().position(5)));
  }

  private ByteBuffer prependTraceCount() {
    int position = 0;
    if (traceCount < 16) {
      buffer.put(4, (byte) (FIXARRAY_PREFIX | traceCount));
      position = 4;
    } else if (traceCount < 0x10000) {
      buffer.put(2, ARRAY16);
      buffer.putShort(3, (short) traceCount);
      position = 2;
    } else {
      buffer.put(0, ARRAY32);
      buffer.putInt(1, traceCount);
    }
    return (ByteBuffer) (buffer.slice().position(position));
  }
}

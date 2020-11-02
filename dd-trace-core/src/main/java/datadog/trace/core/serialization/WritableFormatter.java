package datadog.trace.core.serialization;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Map;

public abstract class WritableFormatter implements Writable, MessageFormatter {

  protected final Codec codec;

  private final ByteBufferConsumer sink;
  protected final ByteBuffer buffer;
  private final boolean manualReset;
  private final int maxArrayHeaderSize;
  protected int messageCount = 0;

  protected WritableFormatter(
      Codec codec,
      ByteBufferConsumer sink,
      ByteBuffer buffer,
      boolean manualReset,
      int maxArrayHeaderSize) {
    this.codec = codec;
    this.sink = sink;
    this.buffer = buffer;
    this.manualReset = manualReset;
    this.maxArrayHeaderSize = maxArrayHeaderSize;
    initBuffer();
  }

  protected void initBuffer() {
    buffer.mark();
  }

  public abstract void reset();

  @Override
  public <T> boolean format(T message, Mapper<T> mapper) {
    try {
      mapper.map(message, this);
      // What happens here is when an entire message is put into the buffer
      // (i.e. it's possible without overflow, we go to the next line, not
      // the catch block) the buffer gets marked (i.e. we take a snapshot
      // of the buffer's current position, where the next message would
      // start). When there is overflow, the buffer is reset, so the
      // position is restored to that snapshot and the serialisation work
      // of `message` is lost.
      //
      // Now there are two cases:
      //
      // 1. That snapshot is the start of the buffer, so the object being mapped
      //    is larger than we have space for. This is unrecoverable, and we're
      //    relying on this not being possible. However, we have hard limits on
      //    what we *can* send to the agent, so all traces have an implicit size
      //    limit. So we need to select a buffer large enough for at least one
      //    trace (and hopefully many).
      // 2. Otherwise, the snapshot position is where the last successfully written
      //    message ended. If we flip the buffer, it can be dispatched to the sink.
      //    After it's been dispatched, we can write the message which caused the
      //    overflow into the now reset buffer, doing the serialisation work again.
      mark();
      return true;
    } catch (BufferOverflowException e) {
      // go back to the last successfully written message
      buffer.reset();
      if (!manualReset) {
        if (buffer.position() == maxArrayHeaderSize) {
          throw e;
        }
        flush();
        return format(message, mapper);
      } else {
        return false;
      }
    }
  }

  public int messageCount() {
    return messageCount;
  }

  protected void mark() {
    buffer.mark();
    ++messageCount;
  }

  @Override
  public void flush() {
    buffer.flip();
    writeHeader();
    sink.accept(messageCount, buffer.slice());
    if (!manualReset) {
      reset();
    }
  }

  protected abstract void writeHeader();

  // NOTE - implementations pulled up to this level should
  // not write directly to the buffer

  @Override
  public void writeMap(
      Map<? extends CharSequence, ? extends Object> map, EncodingCache encodingCache) {
    startMap(map.size());
    for (Map.Entry<? extends CharSequence, ? extends Object> entry : map.entrySet()) {
      writeString(entry.getKey(), encodingCache);
      writeObject(entry.getValue(), encodingCache);
    }
  }

  @Override
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void writeObject(Object value, EncodingCache encodingCache) {
    // unpeel a very common case, but should try to move away from sending
    // UTF8BytesString down this codepath at all
    if (value instanceof UTF8BytesString) {
      writeUTF8((UTF8BytesString) value);
    } else if (null == value) {
      writeNull();
    } else {
      ValueWriter writer = codec.get(value.getClass());
      writer.write(value, this, encodingCache);
    }
  }
}

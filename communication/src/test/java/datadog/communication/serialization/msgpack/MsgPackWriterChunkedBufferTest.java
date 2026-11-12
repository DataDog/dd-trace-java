package datadog.communication.serialization.msgpack;

import datadog.communication.serialization.ByteBufferConsumer;
import datadog.communication.serialization.ChunkedStreamingBuffer;
import datadog.communication.serialization.StreamingBuffer;
import java.nio.ByteBuffer;

/**
 * Runs every {@link MsgPackWriterTest} against a {@link ChunkedStreamingBuffer}, which signals
 * running out of room through {@code mark()} rather than by throwing.
 */
class MsgPackWriterChunkedBufferTest extends MsgPackWriterTest {

  @Override
  StreamingBuffer newBuffer(int capacity, ByteBufferConsumer consumer) {
    return new ChunkedStreamingBuffer(
        capacity,
        (messageCount, chunks) -> {
          int size = 0;
          for (ByteBuffer chunk : chunks) {
            size += chunk.remaining();
          }
          ByteBuffer joined = ByteBuffer.allocate(size);
          for (ByteBuffer chunk : chunks) {
            joined.put(chunk);
          }
          joined.flip();
          consumer.accept(messageCount, joined);
        });
  }
}

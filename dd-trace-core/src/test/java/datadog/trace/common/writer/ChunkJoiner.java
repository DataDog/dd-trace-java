package datadog.trace.common.writer;

import datadog.communication.serialization.ByteBufferConsumer;
import datadog.communication.serialization.ChunkedBufferConsumer;
import java.nio.ByteBuffer;

/** Adapts a single-buffer test consumer to a chunked buffer by joining the chunks. */
public final class ChunkJoiner {

  private ChunkJoiner() {}

  public static ChunkedBufferConsumer joining(ByteBufferConsumer consumer) {
    return (messageCount, chunks) -> {
      int size = 0;
      for (ByteBuffer chunk : chunks) {
        size += chunk.remaining();
      }
      ByteBuffer joined = ByteBuffer.allocate(size);
      for (ByteBuffer chunk : chunks) {
        joined.put(chunk.duplicate());
      }
      joined.flip();
      consumer.accept(messageCount, joined);
    };
  }
}

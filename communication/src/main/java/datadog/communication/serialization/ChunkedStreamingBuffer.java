package datadog.communication.serialization;

import datadog.trace.util.ChunkedBuffer;

/**
 * A {@link StreamingBuffer} over a {@link ChunkedBuffer}: it never makes one large allocation, and
 * a message that doesn't fit makes {@link #mark()} return {@code false} instead of throwing.
 */
public final class ChunkedStreamingBuffer extends ChunkedBuffer implements StreamingBuffer {

  private final ChunkedBufferConsumer consumer;

  public ChunkedStreamingBuffer(int maxBytes, ChunkedBufferConsumer consumer) {
    super(maxBytes);
    this.consumer = consumer;
  }

  /**
   * Hands the completed messages to the consumer, then resets, which also releases the chunks the
   * payload didn't need.
   */
  @Override
  public boolean flush() {
    if (messageCount() == 0) {
      return false;
    }
    try {
      consumer.accept(messageCount(), completedChunks());
    } finally {
      reset();
    }
    return true;
  }
}

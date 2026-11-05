package datadog.communication.serialization;

import datadog.trace.util.ChunkedBuffer;
import java.nio.BufferOverflowException;

/**
 * A {@link StreamingBuffer} over a {@link ChunkedBuffer}: it never makes one large allocation.
 *
 * <p>Like every {@code StreamingBuffer}, it throws {@link BufferOverflowException} as soon as a
 * write doesn't fit, rather than discarding writes until the next mark. Mappers rely on that: an
 * overflow has to stop {@code Mapper.map} before it updates per-payload state such as event counts.
 */
public final class ChunkedStreamingBuffer extends ChunkedBuffer implements StreamingBuffer {

  private final ChunkedBufferConsumer consumer;

  public ChunkedStreamingBuffer(int maxBytes, ChunkedBufferConsumer consumer) {
    super(maxBytes);
    this.consumer = consumer;
  }

  @Override
  protected void onCapacityExceeded() {
    throw new BufferOverflowException();
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

package datadog.trace.common.writer.ddagent;

import datadog.communication.serialization.StreamingBuffer;
import datadog.trace.util.ChunkedBuffer;
import java.nio.BufferOverflowException;

/**
 * The v0.5 string dictionary's buffer. It's chunked, so it holds only as much as the payload's
 * strings need rather than its whole capacity up front, and it never flushes on its own.
 *
 * <p>Traces already written to the payload refer to the dictionary's codes, so a full dictionary
 * can't be emptied on its own: {@link #flush()} throws {@link BufferOverflowException} instead,
 * leaving the dictionary intact, so the trace writer sends the payload (dictionary and traces
 * together), resets the mapper and retries the trace.
 */
final class DictionaryBuffer extends ChunkedBuffer implements StreamingBuffer {

  DictionaryBuffer(int maxBytes) {
    super(maxBytes);
  }

  @Override
  protected void onCapacityExceeded() {
    throw new BufferOverflowException();
  }

  /**
   * @return {@code false} when the dictionary is empty
   * @throws BufferOverflowException when it holds entries, which only the payload can publish
   */
  @Override
  public boolean flush() {
    if (messageCount() == 0) {
      return false;
    }
    throw new BufferOverflowException();
  }
}

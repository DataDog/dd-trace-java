package datadog.communication.serialization;

import java.nio.ByteBuffer;
import java.util.List;

public interface ChunkedBufferConsumer {

  /**
   * Receives completed messages as consecutive parts of one payload, in order. The buffers share
   * the producer's memory, so they're only valid until this call returns.
   */
  void accept(int messageCount, List<ByteBuffer> chunks);
}

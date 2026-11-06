package datadog.trace.common.metrics;

import datadog.communication.serialization.ChunkedBufferConsumer;
import java.nio.ByteBuffer;
import java.util.Collections;

public interface Sink extends ChunkedBufferConsumer {

  /** Accepts a payload held in a single buffer. */
  default void accept(int messageCount, ByteBuffer buffer) {
    accept(messageCount, Collections.singletonList(buffer));
  }

  void register(EventListener listener);
}

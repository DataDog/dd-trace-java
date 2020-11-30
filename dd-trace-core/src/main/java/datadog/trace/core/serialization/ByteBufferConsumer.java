package datadog.trace.core.serialization;

import java.nio.ByteBuffer;

public interface ByteBufferConsumer {

  void accept(int messageCount, ByteBuffer buffer);
}

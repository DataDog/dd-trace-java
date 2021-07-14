package datadog.communication.serialization;

import java.nio.ByteBuffer;

public interface ByteBufferConsumer {

  void accept(int messageCount, ByteBuffer buffer);
}

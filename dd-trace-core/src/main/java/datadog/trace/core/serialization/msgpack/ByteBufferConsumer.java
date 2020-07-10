package datadog.trace.core.serialization.msgpack;

import java.nio.ByteBuffer;

public interface ByteBufferConsumer {

  void accept(int messageCount, ByteBuffer buffer);
}

package datadog.trace.common.pipeline;

import java.nio.ByteBuffer;

public interface Sink {

  void accept(int messageCount, ByteBuffer... buffers);

  void register(EventListener listener);
}

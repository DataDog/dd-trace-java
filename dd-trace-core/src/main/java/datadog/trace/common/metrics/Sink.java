package datadog.trace.common.metrics;

import datadog.communication.serialization.ByteBufferConsumer;

public interface Sink extends ByteBufferConsumer {

  void register(EventListener listener);
}

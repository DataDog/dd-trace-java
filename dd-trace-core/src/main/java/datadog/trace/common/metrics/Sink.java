package datadog.trace.common.metrics;

import datadog.communication.serialization.ChunkedBufferConsumer;

public interface Sink extends ChunkedBufferConsumer {

  void register(EventListener listener);
}

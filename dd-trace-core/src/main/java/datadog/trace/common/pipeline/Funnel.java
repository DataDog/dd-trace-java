package datadog.trace.common.pipeline;

import datadog.trace.core.serialization.ByteBufferConsumer;
import java.nio.ByteBuffer;

public final class Funnel implements ByteBufferConsumer {

  private final Sink sink;

  public Funnel(Sink sink) {
    this.sink = sink;
  }

  @Override
  public void accept(int messageCount, ByteBuffer buffer) {
    sink.accept(messageCount, buffer);
  }
}

package datadog.trace.common.metrics;

import java.nio.ByteBuffer;

/** A {@link Sink} that discards everything. */
public final class NoOpSink implements Sink {

  public static final NoOpSink INSTANCE = new NoOpSink();

  private NoOpSink() {}

  @Override
  public void accept(int messageCount, ByteBuffer buffer) {}

  @Override
  public void register(EventListener listener) {}
}

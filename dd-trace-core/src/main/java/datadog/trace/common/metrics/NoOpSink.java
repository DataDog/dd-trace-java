package datadog.trace.common.metrics;

import java.nio.ByteBuffer;
import java.util.List;

/** A {@link Sink} that discards everything. */
public final class NoOpSink implements Sink {

  public static final NoOpSink INSTANCE = new NoOpSink();

  private NoOpSink() {}

  @Override
  public void accept(int messageCount, List<ByteBuffer> chunks) {}

  @Override
  public void register(EventListener listener) {}
}

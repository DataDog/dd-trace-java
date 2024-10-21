package datadog.trace.api.gateway;

import datadog.trace.api.internal.TraceSegment;
import java.io.Closeable;
import java.io.IOException;
import java.util.function.Function;

/**
 * This is the context that will travel along with the request and be presented to the
 * Instrumentation Gateway subscribers.
 */
public interface RequestContext extends Closeable {
  <T> T getData(RequestContextSlot slot);

  TraceSegment getTraceSegment();

  void setBlockResponseFunction(BlockResponseFunction blockResponseFunction);

  BlockResponseFunction getBlockResponseFunction();

  <T> T getOrCreateMetaStructTop(String key, Function<String, T> defaultValue);

  class Noop implements RequestContext {
    public static final RequestContext INSTANCE = new Noop();

    private Noop() {}

    @Override
    public <T> T getData(RequestContextSlot slot) {
      return null;
    }

    @Override
    public TraceSegment getTraceSegment() {
      return TraceSegment.NoOp.INSTANCE;
    }

    @Override
    public void setBlockResponseFunction(BlockResponseFunction blockResponseFunction) {}

    @Override
    public BlockResponseFunction getBlockResponseFunction() {
      return null;
    }

    @Override
    public <T> T getOrCreateMetaStructTop(String key, Function<String, T> defaultValue) {
      return null;
    }

    @Override
    public void close() throws IOException {}
  }
}

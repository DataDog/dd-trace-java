package datadog.trace.api.gateway;

import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.ClientIpAddressData;
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

  /**
   * Stores the client IP address information resolved during HTTP server request decoration so that
   * later consumers (such as AI Guard) can apply it to the local root span without re-running the
   * resolver. Implementations should store the data on the root request context.
   */
  void setClientIpAddressData(ClientIpAddressData clientIpAddressData);

  /**
   * Returns the previously stored {@link ClientIpAddressData}, or {@code null} if none was stored.
   */
  ClientIpAddressData getClientIpAddressData();

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
    public void setClientIpAddressData(ClientIpAddressData clientIpAddressData) {}

    @Override
    public ClientIpAddressData getClientIpAddressData() {
      return null;
    }

    @Override
    public void close() throws IOException {}
  }
}

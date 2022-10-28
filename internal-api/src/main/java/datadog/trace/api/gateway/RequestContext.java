package datadog.trace.api.gateway;

import datadog.trace.api.internal.TraceSegment;
import java.io.Closeable;

/**
 * This is the context that will travel along with the request and be presented to the
 * Instrumentation Gateway subscribers.
 */
public interface RequestContext extends Closeable {
  <T> T getData(RequestContextSlot slot);

  TraceSegment getTraceSegment();

  void setBlockResponseFunction(BlockResponseFunction blockResponseFunction);

  BlockResponseFunction getBlockResponseFunction();
}

package datadog.trace.agent.test

import datadog.trace.api.gateway.BlockResponseFunction
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.internal.TraceSegment

import java.util.function.Function

/**
 * Decorator for {@link RequestContext} that validates interactions with the request's {@link TraceSegment}:
 * if there is an attempt to modify the trace segment after the root span has finished,
 * an assertion error will be thrown.
 */
class ValidatingRequestContextDecorator implements RequestContext {

  private final RequestContext delegate
  private final TraceSegment traceSegment

  ValidatingRequestContextDecorator(RequestContext delegate, TrackingSpanDecorator spiedAgentSpan, boolean useStrictTraceWrites) {
    this.delegate = delegate

    def segment = delegate.getTraceSegment()
    this.traceSegment = new PreconditionCheckTraceSegment(
      segment, {
        ->
        if (useStrictTraceWrites && spiedAgentSpan.localRootSpan.durationNano != 0) {
          throw new AssertionError("Interaction with TraceSegment after root span has already finished: $spiedAgentSpan")
        }
      }
      )
  }

  @Override
  <T> T getData(RequestContextSlot slot) {
    return delegate.getData(slot)
  }

  @Override
  TraceSegment getTraceSegment() {
    return traceSegment
  }

  @Override
  void setBlockResponseFunction(BlockResponseFunction blockResponseFunction) {
    delegate.setBlockResponseFunction(blockResponseFunction)
  }

  @Override
  BlockResponseFunction getBlockResponseFunction() {
    return delegate.getBlockResponseFunction()
  }

  @Override
  <T> T getOrCreateMetaStructTop(String key, Function<String, T> defaultValue) {
    return delegate.getOrCreateMetaStructTop(key, defaultValue)
  }

  @Override
  void close() throws IOException {
    delegate.close()
  }

  private class PreconditionCheckTraceSegment implements TraceSegment {
    private final Closure check
    private final TraceSegment delegate

    PreconditionCheckTraceSegment(TraceSegment delegate, Closure check) {
      this.delegate = delegate
      this.check = check
    }

    @Override
    void setTagTop(String key, Object value, boolean sanitize) {
      check()
      delegate.setTagTop(key, value, sanitize)
    }

    @Override
    void setTagCurrent(String key, Object value, boolean sanitize) {
      check()
      delegate.setTagCurrent(key, value, sanitize)
    }

    @Override
    Object getTagTop(String key, boolean sanitize) {
      check()
      return delegate.getTagTop(key, sanitize)
    }

    @Override
    Object getTagCurrent(String key, boolean sanitize) {
      check()
      return delegate.getTagCurrent(key, sanitize)
    }

    @Override
    void setDataTop(String key, Object value) {
      check()
      delegate.setDataTop(key, value)
    }

    @Override
    Object getDataTop(String key) {
      check()
      return delegate.getDataTop(key)
    }

    @Override
    void setMetaStructTop(String key, Object value) {
      check()
      delegate.setMetaStructTop(key, value)
    }

    @Override
    void setMetaStructCurrent(String key, Object value) {
      check()
      delegate.setMetaStructCurrent(key, value)
    }

    @Override
    void effectivelyBlocked() {
      check()
      delegate.effectivelyBlocked()
    }

    @Override
    void setDataCurrent(String key, Object value) {
      check()
      delegate.setDataCurrent(key, value)
    }

    @Override
    Object getDataCurrent(String key) {
      check()
      return delegate.getDataCurrent(key)
    }
  }
}

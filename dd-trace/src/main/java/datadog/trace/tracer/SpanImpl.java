package datadog.trace.tracer;

import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Concrete implementation of a span used by applications to collect tracing information.
 *
 * <p>This class is thread-safe.</p>
 */
@Slf4j
class SpanImpl extends AbstractSpan {

  private final List<Interceptor> interceptors;

  /**
   * Create a span with the a specific startTimestamp timestamp.
   *
   * @param trace The trace to associate this span with.
   * @param parentContext identifies the parent of this span. May be null.
   * @param startTimestamp timestamp when this span was started.
   */
  SpanImpl(
    final TraceInternal trace, final SpanContext parentContext, final Timestamp startTimestamp) {
    super(trace, SpanContextImpl.fromParent(parentContext), startTimestamp);

    super.setService(trace.getTracer().getDefaultServiceName());

    interceptors = trace.getTracer().getInterceptors();
    for (final Interceptor interceptor : interceptors) {
      interceptor.afterSpanStarted(this);
    }
  }

  @Override
  public synchronized Long getDuration() {
    return super.getDuration();
  }

  @Override
  public synchronized boolean isFinished() {
    return super.isFinished();
  }

  @Override
  public synchronized String getService() {
    return super.getService();
  }

  @Override
  public synchronized void setService(final String service) {
    if (isFinished()) {
      reportSetterUsageError("service");
    } else {
      super.setService(service);
    }
  }

  @Override
  public synchronized String getResource() {
    return super.getResource();
  }

  @Override
  public synchronized void setResource(final String resource) {
    if (isFinished()) {
      reportSetterUsageError("resource");
    } else {
      super.setResource(resource);
    }
  }

  @Override
  public synchronized String getType() {
    return super.getType();
  }

  @Override
  public synchronized void setType(final String type) {
    if (isFinished()) {
      reportSetterUsageError("type");
    } else {
      super.setType(type);
    }
  }

  @Override
  public synchronized String getName() {
    return super.getName();
  }

  @Override
  public synchronized void setName(final String name) {
    if (isFinished()) {
      reportSetterUsageError("name");
    } else {
      super.setName(name);
    }
  }

  @Override
  public synchronized boolean isErrored() {
    return super.isErrored();
  }

  @Override
  public synchronized void attachThrowable(final Throwable throwable) {
    if (isFinished()) {
      reportSetterUsageError("throwable");
    } else {
      super.attachThrowable(throwable);
    }
  }

  @Override
  public synchronized void setErrored(final boolean errored) {
    if (isFinished()) {
      reportSetterUsageError("errored");
    } else {
      super.setErrored(errored);
    }
  }

  @Override
  protected synchronized Map<String, String> getMetaJsonified() {
    return super.getMetaJsonified();
  }

  @Override
  public synchronized Object getMeta(final String key) {
    return super.getMeta(key);
  }

  @Override
  protected synchronized void setMeta(final String key, final Object value) {
    if (isFinished()) {
      reportSetterUsageError("meta value " + key);
    } else {
      super.setMeta(key, value);
    }
  }

  // FIXME: Add metrics support and json rendering for metrics

  @Override
  public synchronized void finish() {
    if (isFinished()) {
      reportUsageError("Attempted to finish span that is already finished: %s", this);
    } else {
      finishSpan(getStartTimestamp().getDuration(), false);
    }
  }

  @Override
  public synchronized void finish(final long finishTimestampNanoseconds) {
    if (isFinished()) {
      reportUsageError("Attempted to finish span that is already finish: %s", this);
    } else {
      finishSpan(getStartTimestamp().getDuration(finishTimestampNanoseconds), false);
    }
  }

  @Override
  protected synchronized void finalize() {
    try {
      // Note: according to docs finalize is only called once for a given instance - even if
      // instance is 'revived' from the dead by passing reference to some other object and
      // then dies again.
      if (!isFinished()) {
        log.debug(
            "Finishing span due to GC, this will prevent trace from being reported: {}", this);
        finishSpan(getStartTimestamp().getDuration(), true);
      }
    } catch (final Throwable t) {
      // Exceptions thrown in finalizer are eaten up and ignored, so log them instead
      log.debug("Span finalizer had thrown an exception: ", t);
    }
  }

  /**
   * Helper method to perform operations to finish the span.
   *
   * <p>Note: This has to be called under object lock.
   *
   * @param duration duration of the span.
   * @param fromGC true iff we are closing span because it is being GCed, this will make trace
   *     invalid.
   */
  private void finishSpan(final long duration, final boolean fromGC) {
    // Run interceptors in 'reverse' order
    for (int i = interceptors.size() - 1; i >= 0; i--) {
      interceptors.get(i).beforeSpanFinished(this);
    }
    setDuration(duration);
    getTrace().finishSpan(this, fromGC);
  }

  private void reportUsageError(final String message, final Object... args) {
    getTrace().getTracer().reportError(message, args);
  }

  private void reportSetterUsageError(final String fieldName) {
    reportUsageError("Attempted to set '%s' when span is already finished: %s", fieldName, this);
  }

}

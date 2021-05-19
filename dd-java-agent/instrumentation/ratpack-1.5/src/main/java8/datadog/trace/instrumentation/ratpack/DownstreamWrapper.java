package datadog.trace.instrumentation.ratpack;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ratpack.exec.Downstream;

/**
 * Downstream represents the callbacks that get invoked when the promise is completed. As such, we
 * wrap the callbacks to ensure the correct span is in scope. (The span that was in scope when the
 * callback was registered.)
 */
public class DownstreamWrapper<T> implements Downstream<T> {

  private static final Logger log = LoggerFactory.getLogger(DownstreamWrapper.class);
  private final Downstream<T> delegate;
  private final AgentSpan span;

  private DownstreamWrapper(final Downstream<T> delegate, AgentSpan span) {
    this.delegate = delegate;
    this.span = span;
  }

  @Override
  public void success(T value) {
    try (final AgentScope scope = activateSpan(span)) {
      delegate.success(value);
    }
  }

  @Override
  public void error(Throwable throwable) {
    try (final AgentScope scope = activateSpan(span)) {
      delegate.error(throwable);
    }
  }

  @Override
  public void complete() {
    try (final AgentScope scope = activateSpan(span)) {
      delegate.complete();
    }
  }

  public static <T> Downstream<T> wrapIfNeeded(final Downstream<T> delegate, AgentSpan span) {
    if (delegate instanceof DownstreamWrapper) {
      return delegate;
    }
    log.debug("Wrapping Downstream task {}", delegate);
    return new DownstreamWrapper<>(delegate, span == null ? noopSpan() : span);
  }
}

package datadog.trace.instrumentation.apachehttpasyncclient;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopContinuation;
import static datadog.trace.instrumentation.apachehttpasyncclient.ApacheHttpAsyncClientDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.protocol.HttpContext;

public class TraceContinuedFutureCallback<T> implements FutureCallback<T> {
  private final AgentScope.Continuation parentContinuation;
  private final AgentSpan clientSpan;
  private final HttpContext context;
  private final FutureCallback<T> delegate;

  public TraceContinuedFutureCallback(
      final AgentScope.Continuation parentContinuation,
      final AgentSpan clientSpan,
      final HttpContext context,
      final FutureCallback<T> delegate) {
    this.parentContinuation = parentContinuation;
    this.clientSpan = clientSpan;
    this.context = context;
    // Note: this can be null in real life, so we have to handle this carefully
    this.delegate = delegate;
  }

  @Override
  public void completed(final T result) {
    DECORATE.onResponse(clientSpan, context);
    DECORATE.beforeFinish(clientSpan);
    clientSpan.finish(); // Finish span before calling delegate

    if (parentContinuation == noopContinuation()) {
      completeDelegate(result);
    } else {
      try (final AgentScope scope = parentContinuation.activate()) {
        completeDelegate(result);
      }
    }
  }

  @Override
  public void failed(final Exception ex) {
    DECORATE.onResponse(clientSpan, context);
    DECORATE.onError(clientSpan, ex);
    DECORATE.beforeFinish(clientSpan);
    clientSpan.finish(); // Finish span before calling delegate

    if (parentContinuation == noopContinuation()) {
      failDelegate(ex);
    } else {
      try (final AgentScope scope = parentContinuation.activate()) {
        failDelegate(ex);
      }
    }
  }

  @Override
  public void cancelled() {
    DECORATE.onResponse(clientSpan, context);
    DECORATE.beforeFinish(clientSpan);
    clientSpan.finish(); // Finish span before calling delegate

    if (parentContinuation == noopContinuation()) {
      cancelDelegate();
    } else {
      try (final AgentScope scope = parentContinuation.activate()) {
        cancelDelegate();
      }
    }
  }

  private void completeDelegate(final T result) {
    if (delegate != null) {
      delegate.completed(result);
    }
  }

  private void failDelegate(final Exception ex) {
    if (delegate != null) {
      delegate.failed(ex);
    }
  }

  private void cancelDelegate() {
    if (delegate != null) {
      delegate.cancelled();
    }
  }
}

package datadog.trace.instrumentation.apachehttpasyncclient;

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
      final AgentScope parentScope,
      final AgentSpan clientSpan,
      final HttpContext context,
      final FutureCallback<T> delegate) {
    if (parentScope != null) {
      parentContinuation = parentScope.capture();
    } else {
      parentContinuation = null;
    }
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

    if (parentContinuation == null) {
      completeDelegate(result);
    } else {
      try (final AgentScope scope = parentContinuation.activate()) {
        scope.setAsyncPropagation(true);
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

    if (parentContinuation == null) {
      failDelegate(ex);
    } else {
      try (final AgentScope scope = parentContinuation.activate()) {
        scope.setAsyncPropagation(true);
        failDelegate(ex);
      }
    }
  }

  @Override
  public void cancelled() {
    DECORATE.onResponse(clientSpan, context);
    DECORATE.beforeFinish(clientSpan);
    clientSpan.finish(); // Finish span before calling delegate

    if (parentContinuation == null) {
      cancelDelegate();
    } else {
      try (final AgentScope scope = parentContinuation.activate()) {
        scope.setAsyncPropagation(true);
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

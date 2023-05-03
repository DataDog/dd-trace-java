package datadog.trace.instrumentation.apachehttpclient5;

import static datadog.trace.instrumentation.apachehttpclient5.ApacheHttpClientDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.annotation.Nullable;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;

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
    DECORATE.onResponse(clientSpan, getResponseFromHttpContext());
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
    DECORATE.onResponse(clientSpan, getResponseFromHttpContext());
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
    DECORATE.onResponse(clientSpan, getResponseFromHttpContext());
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

  @Nullable
  private HttpResponse getResponseFromHttpContext() {
    return (HttpResponse) context.getAttribute(HttpCoreContext.HTTP_RESPONSE);
  }
}

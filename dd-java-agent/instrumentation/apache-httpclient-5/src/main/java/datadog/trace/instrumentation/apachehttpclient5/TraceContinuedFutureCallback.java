package datadog.trace.instrumentation.apachehttpclient5;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopContinuation;
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
    DECORATE.onResponse(clientSpan, extractHttpResponse(result));
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
    DECORATE.onResponse(clientSpan, extractHttpResponse(null));
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
    DECORATE.onResponse(clientSpan, extractHttpResponse(null));
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

  @Nullable
  private HttpResponse extractHttpResponse(Object futureResult) {
    if (context != null) {
      Object fromContext = context.getAttribute(HttpCoreContext.HTTP_RESPONSE);
      if (fromContext instanceof HttpResponse) {
        return (HttpResponse) fromContext;
      }
    }
    if (futureResult instanceof HttpResponse) {
      return (HttpResponse) futureResult;
    }
    return null;
  }
}

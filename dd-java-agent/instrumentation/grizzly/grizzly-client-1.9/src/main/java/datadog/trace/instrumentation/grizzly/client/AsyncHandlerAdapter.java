package datadog.trace.instrumentation.grizzly.client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.grizzly.client.ClientDecorator.DECORATE;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Response;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public class AsyncHandlerAdapter<T> implements AsyncHandler<T> {
  private final AgentSpan clientSpan;
  private final AgentSpan parentSpan;
  private final AsyncHandler<T> delegate;
  private Response.ResponseBuilder responseBuilder = new Response.ResponseBuilder();

  public AsyncHandlerAdapter(AgentSpan clientSpan, AgentSpan parentSpan, AsyncHandler<T> delegate) {
    this.clientSpan = clientSpan;
    this.parentSpan = parentSpan;
    this.delegate = delegate;
  }

  @Override
  public void onThrowable(Throwable throwable) {
    delegate.onThrowable(throwable);
  }

  @Override
  public STATE onBodyPartReceived(HttpResponseBodyPart httpResponseBodyPart) throws Exception {
    try (final AgentScope ignored = activateSpan(clientSpan)) {
      return delegate.onBodyPartReceived(httpResponseBodyPart);
    }
  }

  @Override
  public STATE onStatusReceived(HttpResponseStatus httpResponseStatus) throws Exception {
    responseBuilder = responseBuilder.accumulate(httpResponseStatus);
    try (final AgentScope ignored = activateSpan(clientSpan)) {
      return delegate.onStatusReceived(httpResponseStatus);
    }
  }

  @Override
  public STATE onHeadersReceived(HttpResponseHeaders httpResponseHeaders) throws Exception {
    responseBuilder = responseBuilder.accumulate(httpResponseHeaders);
    try (final AgentScope ignored = activateSpan(clientSpan)) {
      return delegate.onHeadersReceived(httpResponseHeaders);
    }
  }

  @Override
  public T onCompleted() throws Exception {
    try {
      final T response;
      try (AgentScope ignored = (parentSpan != null ? activateSpan(parentSpan) : null)) {
        response = delegate.onCompleted();
      }
      if (response instanceof Response) {
        DECORATE.onResponse(clientSpan, (Response) response);
      } else {
        DECORATE.onResponse(clientSpan, responseBuilder.build());
      }
      return response;
    } finally {
      DECORATE.beforeFinish(clientSpan);
      clientSpan.finish();
    }
  }
}

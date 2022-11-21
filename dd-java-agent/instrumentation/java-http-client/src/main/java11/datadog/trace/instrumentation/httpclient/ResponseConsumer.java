package datadog.trace.instrumentation.httpclient;

import static datadog.trace.instrumentation.httpclient.JavaNetClientDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.BiConsumer;

public class ResponseConsumer implements BiConsumer<HttpResponse<?>, Throwable> {
  private AgentSpan span;
  private final HttpRequest httpRequest;

  public ResponseConsumer(AgentSpan span, HttpRequest httpRequest) {
    this.span = span;
    this.httpRequest = httpRequest;
  }

  @Override
  public void accept(HttpResponse<?> httpResponse, Throwable throwable) {
    if (throwable != null) {
      DECORATE.onError(span, throwable);
    } else {
      DECORATE.onResponse(span, httpResponse);
    }
    DECORATE.beforeFinish(span);
    span.finish();
    span = null;
  }
}

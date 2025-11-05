package datadog.trace.instrumentation.httpclient;

import static datadog.trace.instrumentation.httpclient.JavaNetClientDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.net.http.HttpResponse;
import java.util.function.BiConsumer;

public class ResponseConsumer implements BiConsumer<HttpResponse<?>, Throwable> {
  private AgentSpan span;

  public ResponseConsumer(AgentSpan span) {
    this.span = span;
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

package datadog.trace.instrumentation.vertx_3_4.server;

import static datadog.trace.instrumentation.vertx_3_4.server.VertxRouterDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerResponse;

public class EndHandlerWrapper implements Handler<Void> {
  private final AgentSpan span;
  private final HttpServerResponse response;

  Handler<Void> actual;

  EndHandlerWrapper(final AgentSpan span, final HttpServerResponse response) {
    this.span = span;
    this.response = response;
  }

  @Override
  public void handle(final Void event) {
    if (actual != null) {
      actual.handle(event);
    }
    DECORATE.onResponse(span, response);
    span.finish();
  }
}

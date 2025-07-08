package datadog.trace.instrumentation.vertx_4_0.server;

import static datadog.trace.instrumentation.vertx_4_0.server.RouteHandlerWrapper.HANDLER_SPAN_CONTEXT_KEY;
import static datadog.trace.instrumentation.vertx_4_0.server.VertxDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

public class EndHandlerWrapper implements Handler<Void> {
  private final RoutingContext routingContext;

  public Handler<Void> actual;

  EndHandlerWrapper(RoutingContext routingContext) {
    this.routingContext = routingContext;
  }

  @Override
  public void handle(final Void event) {
    AgentSpan span = routingContext.get(HANDLER_SPAN_CONTEXT_KEY);
    try {
      if (actual != null) {
        actual.handle(event);
      }
    } finally {
      if (span != null) {
        DECORATE.onResponse(span, routingContext.response());
        span.finish();
      }
    }
  }
}

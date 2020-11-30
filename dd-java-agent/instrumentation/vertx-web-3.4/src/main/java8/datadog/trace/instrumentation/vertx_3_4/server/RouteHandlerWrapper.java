package datadog.trace.instrumentation.vertx_3_4.server;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxRouterDecorator.DECORATE;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxRouterDecorator.INSTRUMENTATION_NAME;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

public class RouteHandlerWrapper implements Handler<RoutingContext> {
  private final Handler<RoutingContext> actual;

  public RouteHandlerWrapper(final Handler<RoutingContext> handler) {
    actual = handler;
  }

  @Override
  public void handle(final RoutingContext routingContext) {
    final AgentSpan parentSpan = activeSpan();
    DECORATE.onRequest(parentSpan, routingContext);

    final AgentSpan span = startSpan(INSTRUMENTATION_NAME);
    routingContext.response().endHandler(new EndHandlerWrapper(span, routingContext.response()));
    DECORATE.afterStart(span);
    span.setResourceName(actual.getClass().getName());

    try (final AgentScope scope = activateSpan(span)) {
      scope.setAsyncPropagation(true);
      try {
        actual.handle(routingContext);
      } catch (final Throwable t) {
        DECORATE.onError(span, t);
        throw t;
      }
    }
  }
}

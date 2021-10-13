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
    AgentSpan span = routingContext.get(AgentSpan.class.getName());
    if (span == null) {
      final AgentSpan parentSpan = activeSpan();
      DECORATE.onRequest(parentSpan, routingContext, routingContext, null);

      span = startSpan(INSTRUMENTATION_NAME);
      routingContext.put(AgentSpan.class.getName(), span);
      // span is stored in the context and the span related work may proceed on any thread
      span.startThreadMigration();

      routingContext.response().endHandler(new EndHandlerWrapper(span, routingContext.response()));
      DECORATE.afterStart(span);
      span.setResourceName(DECORATE.className(actual.getClass()));
    } else {
      // the span was retrieved from the context in 'suspended' state - need to be resumed
      span.finishThreadMigration();
    }

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

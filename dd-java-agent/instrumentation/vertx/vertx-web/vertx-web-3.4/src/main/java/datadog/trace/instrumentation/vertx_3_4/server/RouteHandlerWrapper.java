package datadog.trace.instrumentation.vertx_3_4.server;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.vertx_3_4.server.RouteUpdateHelper.HANDLER_SPAN_CONTEXT_KEY;
import static datadog.trace.instrumentation.vertx_3_4.server.RouteUpdateHelper.PARENT_SPAN_CONTEXT_KEY;
import static datadog.trace.instrumentation.vertx_3_4.server.RouteUpdateHelper.updateRouteFromContext;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxDecorator.DECORATE;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxDecorator.INSTRUMENTATION_NAME;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.impl.RouteImpl;
import io.vertx.ext.web.impl.RouterImpl;

public class RouteHandlerWrapper implements Handler<RoutingContext> {
  private final Handler<RoutingContext> actual;
  private final boolean spanStarter;

  public RouteHandlerWrapper(final Handler<RoutingContext> handler) {
    actual = handler;
    // When mounting a sub router, the handler is a lambda in either RouterImpl or RouteImpl, so
    // this skips that. This prevents routers from creating a span during handling. In the event
    // a route is not found, without this code, a span would be created for the router when it
    // shouldn't
    String name = handler.getClass().getName();
    spanStarter =
        !(name.startsWith(RouterImpl.class.getName())
            || name.startsWith(RouteImpl.class.getName()));
  }

  @Override
  public void handle(final RoutingContext routingContext) {
    AgentSpan span = routingContext.get(HANDLER_SPAN_CONTEXT_KEY);
    if (spanStarter) {
      if (span == null) {
        AgentSpan parentSpan = activeSpan();
        routingContext.put(PARENT_SPAN_CONTEXT_KEY, parentSpan);

        span = startSpan(INSTRUMENTATION_NAME);
        routingContext.put(HANDLER_SPAN_CONTEXT_KEY, span);

        routingContext.response().endHandler(new EndHandlerWrapper(routingContext));
        DECORATE.afterStart(span);
        span.setResourceName(DECORATE.className(actual.getClass()));
      }
      setRoute(routingContext);
    }
    try (final AgentScope scope = span != null ? activateSpan(span) : noopScope()) {
      try {
        actual.handle(routingContext);
      } catch (final Throwable t) {
        if (spanStarter) {
          setRoute(routingContext);
        }
        DECORATE.onError(span, t);
        throw t;
      }
    }
  }

  private void setRoute(RoutingContext routingContext) {
    final AgentSpan parentSpan = routingContext.get(PARENT_SPAN_CONTEXT_KEY);
    final AgentSpan handlerSpan = routingContext.get(HANDLER_SPAN_CONTEXT_KEY);
    if (parentSpan == null && handlerSpan == null) {
      return;
    }
    updateRouteFromContext(routingContext, parentSpan, handlerSpan);
  }
}

package datadog.trace.instrumentation.vertx_3_4.server;

import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;
import static datadog.trace.instrumentation.vertx_3_4.server.RouteHandlerWrapper.HANDLER_SPAN_CONTEXT_KEY;
import static datadog.trace.instrumentation.vertx_3_4.server.RouteHandlerWrapper.PARENT_SPAN_CONTEXT_KEY;
import static datadog.trace.instrumentation.vertx_3_4.server.RouteHandlerWrapper.ROUTE_CONTEXT_KEY;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
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
    AgentSpan parentSpan = routingContext.get(PARENT_SPAN_CONTEXT_KEY);
    String path = routingContext.get(ROUTE_CONTEXT_KEY);
    try {
      if (actual != null) {
        actual.handle(event);
      }
    } finally {
      if (path != null
          && parentSpan != null
          // do not override route with a "/" if it's already set (it's probably more meaningful)
          && !(path.equals("/") && parentSpan.getTag(Tags.HTTP_ROUTE) != null)) {
        HTTP_RESOURCE_DECORATOR.withRoute(
            parentSpan, routingContext.request().rawMethod(), path, true);
      }
      if (span != null) {
        DECORATE.onResponse(span, routingContext.response());
        span.finish();
      }
    }
  }
}

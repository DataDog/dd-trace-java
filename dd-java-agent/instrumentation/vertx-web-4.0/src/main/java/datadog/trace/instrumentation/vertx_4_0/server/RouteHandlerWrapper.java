package datadog.trace.instrumentation.vertx_4_0.server;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.vertx_4_0.server.VertxDecorator.DECORATE;
import static datadog.trace.instrumentation.vertx_4_0.server.VertxDecorator.INSTRUMENTATION_NAME;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RouteHandlerWrapper implements Handler<RoutingContext> {
  private static final Logger log = LoggerFactory.getLogger(RouteHandlerWrapper.class);
  static final String PARENT_SPAN_CONTEXT_KEY = AgentSpan.class.getName() + ".parent";
  static final String HANDLER_SPAN_CONTEXT_KEY = AgentSpan.class.getName() + ".handler";
  static final String ROUTE_CONTEXT_KEY = "dd." + Tags.HTTP_ROUTE;

  private final Handler<RoutingContext> actual;

  public RouteHandlerWrapper(final Handler<RoutingContext> handler) {
    actual = handler;
  }

  @Override
  public void handle(final RoutingContext routingContext) {
    AgentSpan span = routingContext.get(HANDLER_SPAN_CONTEXT_KEY);
    if (span == null) {
      AgentSpan parentSpan = activeSpan();
      routingContext.put(PARENT_SPAN_CONTEXT_KEY, parentSpan);

      span = startSpan(INSTRUMENTATION_NAME);
      routingContext.put(HANDLER_SPAN_CONTEXT_KEY, span);

      routingContext.response().endHandler(new EndHandlerWrapper(routingContext));
      DECORATE.afterStart(span);
      span.setResourceName(DECORATE.className(actual.getClass()));
    }

    updateRoutingContextWithRoute(routingContext);

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

  private void updateRoutingContextWithRoute(RoutingContext routingContext) {
    final String method = routingContext.request().method().name();
    final String mountPoint = routingContext.mountPoint();

    // getName returns the name of the route, if not path or the pattern or null
    String path = routingContext.currentRoute().getName();

    if (mountPoint != null && path != null) {
      final String noBackslashhMountPoint =
          mountPoint.endsWith("/")
              ? mountPoint.substring(0, mountPoint.lastIndexOf("/"))
              : mountPoint;
      path = noBackslashhMountPoint + path;
    }
    if (method != null && path != null) {
      routingContext.put(ROUTE_CONTEXT_KEY, path);
    }
  }
}

package datadog.trace.instrumentation.vertx_3_4.server;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

public class ExceptionHandlerWrapper implements Handler<Throwable> {
  private final RoutingContext routingContext;

  public Handler<Throwable> actual;

  ExceptionHandlerWrapper(RoutingContext routingContext) {
    this.routingContext = routingContext;
  }

  @Override
  public void handle(final Throwable event) {
    try {
      if (actual != null) {
        actual.handle(event);
      }
    } finally {
      RouteHandlerWrapper.finishHandlerSpan(routingContext);
    }
  }
}

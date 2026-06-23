package datadog.trace.instrumentation.vertx_4_0.server;

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
    try {
      if (actual != null) {
        actual.handle(event);
      }
    } finally {
      RouteHandlerWrapper.finishHandlerSpan(routingContext);
    }
  }
}

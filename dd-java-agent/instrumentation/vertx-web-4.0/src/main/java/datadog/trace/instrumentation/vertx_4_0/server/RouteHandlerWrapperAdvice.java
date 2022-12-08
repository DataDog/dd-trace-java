package datadog.trace.instrumentation.vertx_4_0.server;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.impl.RouteImpl;
import net.bytebuddy.asm.Advice;

public class RouteHandlerWrapperAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void wrapHandler(
      @Advice.Argument(value = 0, readOnly = false) Handler<RoutingContext> handler) {
    // When mounting a sub router, the handler is a method reference to the routers handleContext
    // method this skips that. This prevents routers from creating a span during handling. In the
    // event a route is not found, without this code, a span would be created for the router when
    // it shouldn't
    if (!handler.getClass().getName().startsWith(RouteImpl.class.getName())) {
      handler = new RouteHandlerWrapper(handler);
    }
  }
}

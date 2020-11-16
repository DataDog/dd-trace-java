package datadog.trace.instrumentation.vertx_3_4.server;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.impl.RouteImpl;
import net.bytebuddy.asm.Advice;

public class RouteHandlerWrapperAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void wrapHandler(
      @Advice.This final RouteImpl route,
      @Advice.Argument(value = 0, readOnly = false) Handler<RoutingContext> handler) {
    handler = new RouteHandlerWrapper(handler);
  }
}

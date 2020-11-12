package datadog.trace.instrumentation.vertx_3_4.server;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import net.bytebuddy.asm.Advice;

public class RouteHandlerWrapperAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void wrapHandler(
      @Advice.Argument(value = 0, readOnly = false) Handler<RoutingContext> handler) {
    handler = new RouteHandlerWrapper(handler);
  }
}

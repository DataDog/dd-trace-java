package datadog.trace.instrumentation.vertx_4_0.server;

import datadog.trace.bootstrap.InstrumentationContext;
import io.vertx.ext.web.Route;
import net.bytebuddy.asm.Advice;

/** A regex route has no declared path: drops any path stored by an earlier {@code path(..)}. */
public class RouteSetRegexAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(@Advice.This final Route route) {
    InstrumentationContext.get(Route.class, String.class).remove(route);
  }
}

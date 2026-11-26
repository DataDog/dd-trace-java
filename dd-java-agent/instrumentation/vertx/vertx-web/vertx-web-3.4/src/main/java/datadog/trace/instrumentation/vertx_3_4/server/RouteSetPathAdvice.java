package datadog.trace.instrumentation.vertx_3_4.server;

import datadog.trace.bootstrap.InstrumentationContext;
import io.vertx.ext.web.Route;
import net.bytebuddy.asm.Advice;

/**
 * Keeps the path of {@code RouteImpl.setPath(String)} as declared: {@code Route.getPath()} drops
 * the trailing {@code *}, so a catch-all route would look like an exact one.
 */
public class RouteSetPathAdvice {
  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.This final Route route,
      @Advice.Argument(0) final String path,
      @Advice.Thrown final Throwable thrown) {
    if (thrown == null) {
      InstrumentationContext.get(Route.class, String.class).put(route, path);
    }
  }
}

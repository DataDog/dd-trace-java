package datadog.trace.instrumentation.spray;

import net.bytebuddy.asm.Advice;

public class SprayHttpServerRunRouteAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(value = 1, readOnly = false) scala.Function1 route) {
    route = SprayHelper.wrapRoute(route);
  }
}

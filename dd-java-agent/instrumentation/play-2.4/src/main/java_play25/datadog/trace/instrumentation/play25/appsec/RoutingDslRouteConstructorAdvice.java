package datadog.trace.instrumentation.play25.appsec;

import java.util.function.BiFunction;
import java.util.function.Function;
import net.bytebuddy.asm.Advice;
import play.libs.F;

public class RoutingDslRouteConstructorAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  static void before(@Advice.Argument(value = 3, readOnly = false) Object action) {
    if (action instanceof Function) {
      action = new ArgumentCaptureWrappers.ArgumentCaptureFunction<>((Function) action);
    } else if (action instanceof BiFunction) {
      action = new ArgumentCaptureWrappers.ArgumentCaptureBiFunction<>((BiFunction) action);
    } else if (action instanceof F.Function3) {
      action = new ArgumentCaptureWrappers.ArgumentCaptureFunction3<>((F.Function3) action);
    }
  }
}

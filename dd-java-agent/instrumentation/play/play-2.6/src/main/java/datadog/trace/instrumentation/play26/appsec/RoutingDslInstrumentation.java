package datadog.trace.instrumentation.play26.appsec;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.instrumentation.play26.MuzzleReferences;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.bytebuddy.asm.Advice;
import play.libs.F;
import play.routing.RoutingDsl;

/**
 * @see RoutingDsl.Route
 */
@AutoService(InstrumenterModule.class)
public class RoutingDslInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public RoutingDslInstrumentation() {
    super("play");
  }

  @Override
  public String muzzleDirective() {
    return "play26Only";
  }

  @Override
  public String instrumentedType() {
    return "play.routing.RoutingDsl$Route";
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {
      new Reference.Builder("play.routing.RoutingDsl$PathPatternMatcher")
          .withMethod(
              new String[0],
              Reference.EXPECTS_NON_STATIC | Reference.EXPECTS_PUBLIC,
              "routeTo",
              "Lplay/routing/RoutingDsl;",
              "Ljava/util/function/Supplier;")
          .build(),
      MuzzleReferences.PLAY_26_ONLY[0],
      MuzzleReferences.PLAY_26_ONLY[1],
    };
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ArgumentCaptureWrappers",
      packageName + ".ArgumentCaptureWrappers$ArgumentCaptureFunction",
      packageName + ".ArgumentCaptureWrappers$ArgumentCaptureBiFunction",
      packageName + ".ArgumentCaptureWrappers$ArgumentCaptureFunction3",
      packageName + ".PathExtractionHelpers",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor()
            .and(takesArguments(5))
            .and(takesArgument(3, Object.class))
            .and(takesArgument(4, java.lang.reflect.Method.class)),
        RoutingDslInstrumentation.class.getName() + "$RouteConstructorAdvice");
  }

  static class RouteConstructorAdvice {
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
}

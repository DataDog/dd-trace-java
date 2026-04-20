package datadog.trace.instrumentation.play25.appsec;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;

/**
 * @see play.routing.RoutingDsl.Route
 */
@AutoService(InstrumenterModule.class)
public class RoutingDslInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public RoutingDslInstrumentation() {
    super("play");
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return MuzzleReferences.PLAY_25_ONLY;
  }

  @Override
  public String instrumentedType() {
    return "play.routing.RoutingDsl$Route";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ArgumentCaptureWrappers",
      packageName + ".ArgumentCaptureWrappers$ArgumentCaptureFunction",
      packageName + ".ArgumentCaptureWrappers$ArgumentCaptureBiFunction",
      packageName + ".ArgumentCaptureWrappers$ArgumentCaptureFunction3",
      "datadog.trace.instrumentation.play.appsec.PathExtractionHelpers",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor()
            .and(takesArguments(5))
            .and(takesArgument(3, Object.class))
            .and(takesArgument(4, java.lang.reflect.Method.class)),
        packageName + ".RoutingDslRouteConstructorAdvice");
  }
}

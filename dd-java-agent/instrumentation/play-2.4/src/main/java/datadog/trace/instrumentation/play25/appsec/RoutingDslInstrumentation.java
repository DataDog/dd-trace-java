package datadog.trace.instrumentation.play25.appsec;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterGroup;
import datadog.trace.agent.tooling.muzzle.Reference;

/** @see play.routing.RoutingDsl.Route */
@AutoService(Instrumenter.class)
public class RoutingDslInstrumentation extends InstrumenterGroup.AppSec
    implements Instrumenter.ForSingleType {
  public RoutingDslInstrumentation() {
    super("play");
  }

  @Override
  public String muzzleDirective() {
    return "play25only";
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
        packageName + ".RoutingDslRouteConstructorAdvice");
  }
}

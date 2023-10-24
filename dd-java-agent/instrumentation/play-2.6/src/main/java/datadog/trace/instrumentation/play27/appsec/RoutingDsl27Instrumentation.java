package datadog.trace.instrumentation.play27.appsec;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.Reference;
import play.routing.RoutingDsl;

/** @see RoutingDsl.Route */
@AutoService(Instrumenter.class)
public class RoutingDsl27Instrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForSingleType {
  public RoutingDsl27Instrumentation() {
    super("play");
  }

  @Override
  public String muzzleDirective() {
    return "play27";
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
              "routingTo",
              "Lplay/routing/RoutingDsl;",
              "Lplay/routing/RequestFunctions$Params1;")
          .build()
    };
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ArgumentCaptureAdvice",
      packageName + ".ArgumentCaptureAdvice$ArgumentCaptureFunctionParam1",
      packageName + ".ArgumentCaptureAdvice$ArgumentCaptureFunctionParam2",
      packageName + ".ArgumentCaptureAdvice$ArgumentCaptureFunctionParam3",
      "datadog.trace.instrumentation.play26.appsec.PathExtractionHelpers",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor()
            .and(takesArguments(5))
            .and(takesArgument(3, Object.class))
            .and(takesArgument(4, java.lang.reflect.Method.class)),
        packageName + ".ArgumentCaptureAdvice$RouteConstructorAdvice");
  }
}

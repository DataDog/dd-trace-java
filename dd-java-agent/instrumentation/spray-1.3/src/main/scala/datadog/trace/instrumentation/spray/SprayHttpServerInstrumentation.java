package datadog.trace.instrumentation.spray;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class SprayHttpServerInstrumentation extends Instrumenter.Tracing {
  public SprayHttpServerInstrumentation() {
    super("spray-http", "spray-http-server");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("spray.routing.HttpServiceBase$class");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SprayHeaders",
      packageName + ".SprayHelper",
      packageName + ".SprayHelper$",
      packageName + ".SprayHelper$$anonfun$wrapRequestContext$1",
      packageName + ".SprayHelper$$anonfun$wrapRoute$1",
      packageName + ".SprayHttpServerDecorator",
    };
  }

  /**
   * Spray has 'nested' function called runSealedRoute that runs route with all handlers wrapped
   * around it. This gives us access to a 'final' response that we can use to get all data for the
   * span. Unfortunately this hides from us exception that might have been through by the route. In
   * order to capture that exception we have to also wrap 'inner' route.
   */
  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("runSealedRoute$1").and(takesArgument(1, named("spray.routing.RequestContext"))),
        packageName + ".SprayHttpServerRunSealedRouteAdvice");
    transformation.applyAdvice(
        named("runRoute").and(takesArgument(1, named("scala.Function1"))),
        packageName + ".SprayHttpServerRunRouteAdvice");
  }
}

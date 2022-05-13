package datadog.trace.instrumentation.vertx_4_0.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.IReferenceMatcher;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;

@AutoService(Instrumenter.class)
public class RouteImplInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForKnownTypes {

  public RouteImplInstrumentation() {
    super("vertx", "vertx-4.2");
  }

  private IReferenceMatcher postProcessReferenceMatcher(final ReferenceMatcher origMatcher) {
    return new IReferenceMatcher.ConjunctionReferenceMatcher(
        origMatcher, VertxVersionMatcher.INSTANCE);
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.vertx.ext.web.impl.RouteImpl", "io.vertx.ext.web.impl.RouteState",
    };
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".PathParameterPublishingHelper",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("matches")
            .and(takesArguments(3))
            .and(takesArgument(0, named("io.vertx.ext.web.impl.RoutingContextImplBase")))
            .and(takesArgument(1, String.class))
            .and(takesArgument(2, boolean.class))
            .and(isPublic())
            .and(returns(int.class)),
        packageName + ".RouteMatchesAdvice");

    transformation.applyAdvice(
        named("matches")
            .and(takesArguments(3))
            .and(
                takesArgument(
                    0,
                    named("io.vertx.ext.web.impl.RoutingContextImplBase")
                        .or(named("io.vertx.ext.web.RoutingContext"))))
            .and(takesArgument(1, String.class))
            .and(takesArgument(2, boolean.class))
            .and(returns(boolean.class)),
        packageName + ".RouteMatchesAdvice$BooleanReturnVariant");
  }
}

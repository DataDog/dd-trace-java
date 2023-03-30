package datadog.trace.instrumentation.vertx_3_4.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxVersionMatcher.PARSABLE_HEADER_VALUE;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxVersionMatcher.VIRTUAL_HOST_HANDLER;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.Reference;
import java.util.Set;

@AutoService(Instrumenter.class)
public class RouteImplInstrumentation extends Instrumenter.Default
    implements Instrumenter.ForKnownTypes {

  public RouteImplInstrumentation() {
    super("vertx", "vertx-3.4");
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {PARSABLE_HEADER_VALUE, VIRTUAL_HOST_HANDLER};
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return enabledSystems.contains(TargetSystem.APPSEC)
        || enabledSystems.contains(TargetSystem.IAST);
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

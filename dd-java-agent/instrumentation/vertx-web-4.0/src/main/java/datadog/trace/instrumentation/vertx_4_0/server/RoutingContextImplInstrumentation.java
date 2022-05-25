package datadog.trace.instrumentation.vertx_4_0.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.IReferenceMatcher;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;

@AutoService(Instrumenter.class)
public class RoutingContextImplInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForSingleType {

  public RoutingContextImplInstrumentation() {
    super("vertx", "vertx-4.0");
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.ext.web.impl.RoutingContextImpl";
  }

  private IReferenceMatcher postProcessReferenceMatcher(final ReferenceMatcher origMatcher) {
    return new IReferenceMatcher.ConjunctionReferenceMatcher(
        origMatcher, VertxVersionMatcher.INSTANCE);
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
      named("getBodyAsJson")
        .or(named("getBodyAsJsonArray"))
        .and(takesArguments(1).and(takesArgument(0, int.class))),
      packageName + ".RoutingContextJsonAdvice");
  }
}

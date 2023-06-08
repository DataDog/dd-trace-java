package datadog.trace.instrumentation.resteasy;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

@AutoService(Instrumenter.class)
public class PathParamInjectorInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  public PathParamInjectorInstrumentation() {
    super("resteasy");
  }

  @Override
  public String muzzleDirective() {
    return "jaxrs";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("inject").and(isPublic()).and(takesArguments(2)),
        packageName + ".PathParamInjectorAdvice");
  }

  @Override
  public String instrumentedType() {
    return "org.jboss.resteasy.core.PathParamInjector";
  }
}

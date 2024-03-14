package datadog.trace.instrumentation.resteasy;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

@AutoService(InstrumenterModule.class)
public class PathParamInjectorInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType {

  public PathParamInjectorInstrumentation() {
    super("resteasy");
  }

  @Override
  public String muzzleDirective() {
    return "jaxrs";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("inject").and(isPublic()).and(takesArguments(2)),
        packageName + ".PathParamInjectorAdvice");
  }

  @Override
  public String instrumentedType() {
    return "org.jboss.resteasy.core.PathParamInjector";
  }
}

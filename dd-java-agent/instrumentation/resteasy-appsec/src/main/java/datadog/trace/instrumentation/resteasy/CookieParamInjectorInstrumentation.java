package datadog.trace.instrumentation.resteasy;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

@AutoService(Instrumenter.class)
public class CookieParamInjectorInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  public CookieParamInjectorInstrumentation() {
    super("Cookie Param Value Injector for RestEasy");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("inject").and(isPublic()), packageName + ".CookieParamInjectorAdvice");
  }

  @Override
  public String instrumentedType() {
    return "org.jboss.resteasy.core.CookieParamInjector";
  }
}

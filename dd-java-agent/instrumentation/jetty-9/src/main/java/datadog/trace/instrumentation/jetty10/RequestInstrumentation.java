package datadog.trace.instrumentation.jetty10;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

@AutoService(Instrumenter.class)
public final class RequestInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public RequestInstrumentation() {
    super("jetty");
  }

  @Override
  public String muzzleDirective() {
    return "10_series";
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.Request";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("setContextPath").and(takesArgument(0, String.class)),
        packageName + ".SetContextPathAdvice");
    transformation.applyAdvice(
        named("setServletPath").and(takesArgument(0, String.class)),
        packageName + ".SetServletPathAdvice");
  }
}

package datadog.trace.instrumentation.jetty10;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

@AutoService(InstrumenterModule.class)
public final class RequestInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public RequestInstrumentation() {
    super("jetty");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.Request";
  }

  public String muzzleDirective() {
    return "10_series";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("setContextPath").and(takesArgument(0, String.class)),
        packageName + ".SetContextPathAdvice");
    transformer.applyAdvice(
        named("setServletPath").and(takesArgument(0, String.class)),
        packageName + ".SetServletPathAdvice");
    transformer.applyAdvice(
        named("setRequestedSessionId").and(takesArgument(0, String.class)),
        packageName + ".SetRequestedSessionIdAdvice");
  }
}

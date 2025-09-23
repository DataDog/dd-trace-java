package datadog.trace.instrumentation.jetty12;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

@AutoService(InstrumenterModule.class)
public final class AbstractSessionManagerInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public AbstractSessionManagerInstrumentation() {
    super("jetty");
  }

  @Override
  public String muzzleDirective() {
    return "jetty-session";
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.session.AbstractSessionManager";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("resolveRequestedSessionId")
            .and(isProtected())
            .and(takesArguments(1))
            .and(takesArgument(0, named("org.eclipse.jetty.server.Request"))),
        packageName + ".ResolveRequestedSessionIdAdvice");
  }
}

package datadog.trace.instrumentation.jetty12;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

/**
 * Request-phase AppSec blocking for Jetty 12: commits the blocking response decided while
 * processing the request before {@code Server.handle} dispatches to the handler chain. Covers every
 * environment (ee8, ee9, ee10) since they all sit below {@code Server.handle}.
 */
@AutoService(InstrumenterModule.class)
public final class JettyServerHandleBlockingInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public JettyServerHandleBlockingInstrumentation() {
    super("jetty");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.Server";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JettyBlockResponseFunction", packageName + ".JettyBlockResponseHelper"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("handle")
            .and(isPublic())
            .and(takesArguments(3))
            .and(takesArgument(0, named("org.eclipse.jetty.server.Request")))
            .and(takesArgument(1, named("org.eclipse.jetty.server.Response")))
            .and(takesArgument(2, named("org.eclipse.jetty.util.Callback")))
            .and(returns(boolean.class)),
        packageName + ".JettyServerHandleBlockingAdvice");
  }
}

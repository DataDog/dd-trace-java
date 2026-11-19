package datadog.trace.instrumentation.jetty12;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

/**
 * Completes a Jetty ee10 async request left suspended by an AppSec blocking response.
 *
 * <p>From Jetty 12.1, {@code ServletChannelState.onError} returns {@code false} for an ASYNC
 * request without an {@code AsyncListener}: no sendError, no abort, no complete. When a {@code
 * BlockingException} is thrown after {@code startAsync()} (e.g. {@code ProxyServlet} with RASP
 * SSRF), the blocking response is committed but the request stays suspended for ever: the
 * connection hangs and the server span is never finished.
 *
 * <p>{@code ServletChannelState} with a boolean {@code onError} only exists from ee10 12.0.16,
 * hence the dedicated muzzle directive, so that apps without ee10 keep the other Jetty 12 modules.
 */
@AutoService(InstrumenterModule.class)
public final class JettyServletChannelStateInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public JettyServletChannelStateInstrumentation() {
    super("jetty");
  }

  @Override
  public String muzzleDirective() {
    return "jetty-ee10-servlet";
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.ee10.servlet.ServletChannelState";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JettyBlockingExceptions", packageName + ".JettyAsyncBlockCompletion"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("onError")
            .and(takesArguments(1))
            .and(takesArgument(0, named("java.lang.Throwable")))
            .and(returns(boolean.class)),
        packageName + ".JettyServletChannelStateOnErrorAdvice");
  }
}

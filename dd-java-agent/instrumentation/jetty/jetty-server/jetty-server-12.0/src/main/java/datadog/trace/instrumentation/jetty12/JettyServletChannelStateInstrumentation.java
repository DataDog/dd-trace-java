package datadog.trace.instrumentation.jetty12;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

/**
 * Hooks the exit of {@code ServletChannelState.onError(Throwable)} so that {@code
 * JettyAsyncBlockCompletion} can complete an ee10 async request suspended by a blocking response.
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
      packageName + ".JettyBlockResponseFunction",
      packageName + ".JettyDeferredCompletion",
      packageName + ".JettyBlockResponseHelper",
      packageName + ".JettyBlockingExceptions",
      packageName + ".JettyAsyncBlockCompletion"
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

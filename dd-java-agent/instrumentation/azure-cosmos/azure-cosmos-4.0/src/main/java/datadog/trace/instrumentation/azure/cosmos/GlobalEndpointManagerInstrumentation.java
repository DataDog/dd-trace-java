package datadog.trace.instrumentation.azure.cosmos;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;

/**
 * Keeps the SDK's background database account refresh out of the trace that was active when the
 * client was built.
 *
 * <p>{@code GlobalEndpointManager} refreshes the account on a timer for as long as the client
 * lives. {@code startRefreshLocationTimerAsync()} arms the next refresh by subscribing to a delayed
 * {@code Mono} on the SDK's scheduler. Its first call happens while the client is built, so the
 * reactor and executor instrumentations capture the caller's span there, and each later call
 * re-arms the timer from inside the previous refresh, which still carries that span: every refresh
 * would join the caller's trace. Arming the timer under a no-op span leaves nothing to capture, so
 * each refresh starts its own trace, as it does for a client built outside any span.
 */
public final class GlobalEndpointManagerInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "com.azure.cosmos.implementation.GlobalEndpointManager";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("startRefreshLocationTimerAsync")).and(takesArguments(0)),
        getClass().getName() + "$RefreshTimerAdvice");
  }

  public static final class RefreshTimerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope enter() {
      return activateSpan(noopSpan());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter final ContextScope scope) {
      scope.close();
    }
  }
}

package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.context.TraceScope;
import net.bytebuddy.asm.Advice;
import reactor.core.Disposables;

public class DisableAsyncAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope enter() {
    final TraceScope scope = activeScope();
    if (scope != null && scope.isAsyncPropagating()) {
      return activateSpan(noopSpan());
    }
    return null;
  }

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void exit(@Advice.Enter final AgentScope scope) {
    if (scope != null) {
      scope.close();
      // Don't need to finish noop span.
    }
  }

  public static void muzzleCheck() {
    // added in 3.1, the class we care about is package private so we can't reference it directly
    Disposables.single();
  }
}

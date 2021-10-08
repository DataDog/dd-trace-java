package datadog.trace.instrumentation.mule4;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;
import org.mule.runtime.core.internal.connector.SchedulerController;

public class HttpRequesterDoRequestAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope enter() {
    return activateSpan(noopSpan());
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void exit(@Advice.Enter final AgentScope scope) {
    scope.close();
  }

  public static void muzzleCheck(
      // Moved in 4.0
      SchedulerController controller) {
    controller.isPrimarySchedulingInstance();
  }
}

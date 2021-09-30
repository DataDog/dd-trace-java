package datadog.trace.instrumentation.mule4;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import java.util.concurrent.RunnableFuture;
import net.bytebuddy.asm.Advice;
import org.mule.runtime.core.internal.connector.SchedulerController;

public class DefaultSchedulerAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onEnter() {
    CallDepthThreadLocalMap.incrementCallDepth(RunnableFuture.class);
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void onExit() {
    CallDepthThreadLocalMap.decrementCallDepth(RunnableFuture.class);
  }

  public static void muzzleCheck(
      // Moved in 4.0
      SchedulerController controller) {
    controller.isPrimarySchedulingInstance();
  }
}

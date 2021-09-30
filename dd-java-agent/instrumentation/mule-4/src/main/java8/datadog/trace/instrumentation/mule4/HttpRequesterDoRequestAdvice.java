package datadog.trace.instrumentation.mule4;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import java.util.concurrent.CompletableFuture;
import net.bytebuddy.asm.Advice;
import org.mule.runtime.core.internal.connector.SchedulerController;

public class HttpRequesterDoRequestAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onEnter() {
    CallDepthThreadLocalMap.incrementCallDepth(CompletableFuture.class);
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void onExit() {
    CallDepthThreadLocalMap.decrementCallDepth(CompletableFuture.class);
  }

  public static void muzzleCheck(
      // Moved in 4.0
      SchedulerController controller) {
    controller.isPrimarySchedulingInstance();
  }
}

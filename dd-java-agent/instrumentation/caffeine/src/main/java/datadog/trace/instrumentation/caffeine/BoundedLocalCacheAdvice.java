package datadog.trace.instrumentation.caffeine;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import java.util.concurrent.ForkJoinPool;
import net.bytebuddy.asm.Advice;

public class BoundedLocalCacheAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onEnter() {
    CallDepthThreadLocalMap.incrementCallDepth(ForkJoinPool.class);
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void onExit() {
    CallDepthThreadLocalMap.decrementCallDepth(ForkJoinPool.class);
  }
}

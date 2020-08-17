package datadog.trace.core.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ThreadUtil {

  public static void onSpinWait() {
    if (null != SPIN_WAIT) {
      try {
        SPIN_WAIT.invokeExact();
      } catch (Throwable ignored) {
      }
    }
  }

  private static final MethodHandle SPIN_WAIT = findSpinWaitMethodHandle();

  private static MethodHandle findSpinWaitMethodHandle() {
    try {
      return MethodHandles.publicLookup()
          .findStatic(Thread.class, "onSpinWait", MethodType.methodType(void.class));
    } catch (NoSuchMethodException | IllegalAccessException ignored) {
      log.debug("Thread.onSpinWait not found, will use no-op");
    }
    return null;
  }
}

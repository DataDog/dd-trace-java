package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;

import java.lang.reflect.Method;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class NewTaskFor {

  private static final boolean SAFE_TO_PROPAGATE;
  private static final Method NEW_TASK_FOR_RUNNABLE;

  static {
    boolean safeToPropagate = false;
    Method newTaskFor = null;
    try {
      newTaskFor =
          AbstractExecutorService.class.getDeclaredMethod(
              "newTaskFor", Runnable.class, Object.class);
      newTaskFor.setAccessible(true);
      safeToPropagate = true;
    } catch (Throwable error) {
      log.debug("Failed to create method accessor for newTaskFor", error);
    }
    SAFE_TO_PROPAGATE = safeToPropagate;
    NEW_TASK_FOR_RUNNABLE = newTaskFor;
  }

  @SuppressWarnings("unchecked")
  public static Runnable newTaskFor(Executor executor, Runnable runnable) {
    // TODO write a slick instrumentation and instrument these types directly
    if (runnable instanceof RunnableFuture
        || null == runnable
        || !SAFE_TO_PROPAGATE
        || exclude(RUNNABLE, runnable)
        || runnable.getClass().getName().startsWith("slick.")) {
      return runnable;
    }
    if (null != NEW_TASK_FOR_RUNNABLE && executor instanceof AbstractExecutorService) {
      try {
        return (RunnableFuture<Void>) NEW_TASK_FOR_RUNNABLE.invoke(executor, runnable, null);
      } catch (Throwable ignore ) { }
    }
    return new FutureTask<>(runnable, null);
  }
}

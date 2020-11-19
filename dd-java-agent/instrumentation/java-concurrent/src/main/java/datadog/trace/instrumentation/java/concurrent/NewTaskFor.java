package datadog.trace.instrumentation.java.concurrent;

import java.lang.reflect.Method;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.RunnableFuture;
import lombok.extern.slf4j.Slf4j;

// FIXME: DELETE
@Slf4j
public final class NewTaskFor {

  private static final Method NEW_TASK_FOR_RUNNABLE;

  static {
    Method newTaskFor = null;
    try {
      newTaskFor =
          AbstractExecutorService.class.getDeclaredMethod(
              "newTaskFor", Runnable.class, Object.class);
      newTaskFor.setAccessible(true);
    } catch (Throwable error) {
      log.debug("Failed to create method accessor for newTaskFor", error);
    }
    NEW_TASK_FOR_RUNNABLE = newTaskFor;
  }

  @SuppressWarnings("unchecked")
  public static Runnable newTaskFor(Executor executor, Runnable runnable) {
    try {
      return (RunnableFuture<Void>) NEW_TASK_FOR_RUNNABLE.invoke(executor, runnable, null);
    } catch (Throwable t) {
      log.debug("failed to invoke newTaskFor on {}", executor, t);
    }
    throw new IllegalStateException();
  }
}

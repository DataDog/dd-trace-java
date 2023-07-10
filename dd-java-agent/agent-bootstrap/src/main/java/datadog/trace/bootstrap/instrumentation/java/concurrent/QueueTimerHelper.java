package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.TaskWrapper.getUnwrappedType;

import datadog.trace.api.profiling.QueueTiming;
import datadog.trace.api.profiling.Timer;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;

public class QueueTimerHelper {

  public static <T> void startQueuingTimer(
      ContextStore<T, State> taskContextStore, Class<?> schedulerClass, T task) {
    State state = taskContextStore.get(task);
    if (task != null && state != null) {
      QueueTiming timing =
          (QueueTiming) AgentTracer.get().getTimer().start(Timer.TimerType.QUEUEING);
      timing.setTask(getUnwrappedType(task));
      timing.setScheduler(schedulerClass);
      state.setTiming(timing);
    }
  }

  public static <T> Class<?> unwrap(T task) {
    return getUnwrappedType(task);
  }

  public static <T> void startQueuingTimer(
      State state, Class<?> schedulerClass, Class<?> unwrappedTaskClass, T task) {
    if (task != null) {
      QueueTiming timing =
          (QueueTiming) AgentTracer.get().getTimer().start(Timer.TimerType.QUEUEING);
      timing.setTask(unwrappedTaskClass);
      timing.setScheduler(schedulerClass);
      state.setTiming(timing);
    }
  }
}

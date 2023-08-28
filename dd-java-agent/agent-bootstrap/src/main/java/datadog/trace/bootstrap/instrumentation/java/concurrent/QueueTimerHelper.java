package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.api.profiling.QueueTiming;
import datadog.trace.api.profiling.Timer;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;

public class QueueTimerHelper {

  public static <T> void startQueuingTimer(
      ContextStore<T, State> taskContextStore, Class<?> schedulerClass, T task) {
    State state = taskContextStore.get(task);
    startQueuingTimer(state, schedulerClass, task);
  }

  public static void startQueuingTimer(State state, Class<?> schedulerClass, Object task) {
    if (task != null && state != null) {
      QueueTiming timing =
          (QueueTiming) AgentTracer.get().getTimer().start(Timer.TimerType.QUEUEING);
      timing.setTask(task);
      timing.setScheduler(schedulerClass);
      state.setTiming(timing);
    }
  }
}

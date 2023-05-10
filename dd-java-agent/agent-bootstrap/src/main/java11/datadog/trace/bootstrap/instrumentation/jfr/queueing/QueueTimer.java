package datadog.trace.bootstrap.instrumentation.jfr.queueing;

import datadog.trace.api.Config;
import datadog.trace.api.Platform;

public class QueueTimer {
  private static final boolean ENABLED = Platform.hasJfr() && Config.get().isProfilingEnabled();

  public static Object startTimer(Class<?> task, Class<?> queue, Class<?> executor) {
    if (ENABLED) {
      QueueTimeEvent event = new QueueTimeEvent(task, queue, executor);
      if (event.isEnabled()) {
        return event;
      }
    }
    return null;
  }

  public static void stopTimer(Object timer) {
    if (ENABLED && timer instanceof QueueTimeEvent) {
      ((QueueTimeEvent) timer).close();
    }
  }
}

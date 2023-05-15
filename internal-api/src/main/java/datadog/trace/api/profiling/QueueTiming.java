package datadog.trace.api.profiling;

public interface QueueTiming extends Timing {
  void setQueue(Class<?> queue);

  void setTask(Class<?> task);

  void setScheduler(Class<?> scheduler);
}

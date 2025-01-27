package datadog.trace.api.profiling;

public interface QueueTiming extends Timing {

  void setTask(Object task);

  void setScheduler(Class<?> scheduler);

  void setQueue(Class<?> queue);

  void setQueueLength(int queueLength);
}

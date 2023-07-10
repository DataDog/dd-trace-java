package datadog.trace.api.profiling;

public interface QueueTiming extends Timing {

  void setTask(Class<?> task);

  void setScheduler(Class<?> scheduler);
}

package datadog.trace.api.profiling;

public interface QueueTiming extends Timing {

  void setTask(Object task);

  void setScheduler(Class<?> scheduler);
}

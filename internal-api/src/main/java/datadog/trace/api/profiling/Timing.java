package datadog.trace.api.profiling;

public interface Timing extends AutoCloseable {
  @Override
  void close();

  class NoOp implements Timing, QueueTiming {
    public static final Timing INSTANCE = new NoOp();

    @Override
    public void close() {}

    @Override
    public void setTask(Object task) {}

    @Override
    public void setScheduler(Class<?> scheduler) {}
  }
}

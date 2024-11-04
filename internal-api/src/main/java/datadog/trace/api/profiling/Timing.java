package datadog.trace.api.profiling;

public interface Timing {
  void report();

  boolean sample();

  class NoOp implements Timing, QueueTiming {
    public static final Timing INSTANCE = new NoOp();

    @Override
    public void report() {}

    @Override
    public boolean sample() {
      return false;
    }

    @Override
    public void setTask(Object task) {}

    @Override
    public void setScheduler(Class<?> scheduler) {}
  }
}

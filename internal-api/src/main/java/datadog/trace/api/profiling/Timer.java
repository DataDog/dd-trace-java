package datadog.trace.api.profiling;

public interface Timer {

  enum TimerType {
    QUEUEING
  }

  Timing start(TimerType type);

  final class NoOp implements Timer {

    public static final Timer INSTANCE = new NoOp();

    @Override
    public Timing start(TimerType type) {
      return Timing.NoOp.INSTANCE;
    }
  }
}

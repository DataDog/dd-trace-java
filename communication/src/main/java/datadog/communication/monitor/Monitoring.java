package datadog.communication.monitor;

public interface Monitoring {
  Monitoring DISABLED = new DisabledMonitoring();

  Recording newTimer(String name);

  Recording newTimer(String name, String... tags);

  Recording newThreadLocalTimer(String name);

  Counter newCounter(String name);

  class DisabledMonitoring implements Monitoring {
    private DisabledMonitoring() {}

    @Override
    public Recording newTimer(String name) {
      return NoOpRecording.NO_OP;
    }

    @Override
    public Recording newTimer(String name, String... tags) {
      return NoOpRecording.NO_OP;
    }

    @Override
    public Recording newThreadLocalTimer(String name) {
      return NoOpRecording.NO_OP;
    }

    @Override
    public Counter newCounter(String name) {
      return NoOpCounter.NO_OP;
    }
  }
}

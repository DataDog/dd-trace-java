package datadog.metrics.api;

final class NoOpMonitoring implements Monitoring {
  NoOpMonitoring() {}

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

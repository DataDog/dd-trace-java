package datadog.metrics.api;

final class NoOpRecording extends Recording {

  public static final Recording NO_OP = new NoOpRecording();

  @Override
  public Recording start() {
    return this;
  }

  @Override
  public void reset() {}

  @Override
  public void stop() {}

  @Override
  public void flush() {}
}

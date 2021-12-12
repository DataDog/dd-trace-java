package datadog.trace.api.time;

public final class ControllableTimeSource implements TimeSource {
  private long currentTime = 0;

  public void advance(long nanosIncrement) {
    currentTime += nanosIncrement;
  }

  public void set(long nanos) {
    currentTime = nanos;
  }

  @Override
  public long getNanoTime() {
    return currentTime;
  }
}

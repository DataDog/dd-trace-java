package datadog.trace.api.time;

import java.util.concurrent.TimeUnit;

public class ControllableTimeSource implements TimeSource {
  private long currentTime = 0;

  public void advance(long nanosIncrement) {
    currentTime += nanosIncrement;
  }

  public void set(long nanos) {
    currentTime = nanos;
  }

  @Override
  public long getNanoTicks() {
    return currentTime;
  }

  @Override
  public long getCurrentTimeMillis() {
    return TimeUnit.NANOSECONDS.toMillis(currentTime);
  }

  @Override
  public long getCurrentTimeMicros() {
    return TimeUnit.NANOSECONDS.toMicros(currentTime);
  }

  @Override
  public long getCurrentTimeNanos() {
    return currentTime;
  }
}

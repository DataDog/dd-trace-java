package datadog.trace.api.time;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class SystemTimeSource implements TimeSource {
  public static final TimeSource INSTANCE = new SystemTimeSource();

  private SystemTimeSource() {}

  @Override
  public long getNanoTicks() {
    return System.nanoTime();
  }

  @Override
  public long getCurrentTimeMillis() {
    return System.currentTimeMillis();
  }

  @Override
  public long getCurrentTimeMicros() {
    return MILLISECONDS.toMicros(getCurrentTimeMillis());
  }

  @Override
  public long getCurrentTimeNanos() {
    return MILLISECONDS.toNanos(getCurrentTimeMillis());
  }
}

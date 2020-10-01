package datadog.trace.api.time;

public class SystemTimeSource implements TimeSource {
  public static final TimeSource INSTANCE = new SystemTimeSource();

  @Override
  public long getNanoTime() {
    return System.nanoTime();
  }
}

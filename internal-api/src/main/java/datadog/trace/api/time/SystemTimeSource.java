package datadog.trace.api.time;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.Config;

public class SystemTimeSource implements TimeSource {
  public static final TimeSource INSTANCE = new SystemTimeSource();
  /** Time source start time in nanoseconds measured up to a millisecond accuracy */
  private final long startTimeNano;
  /** Nanosecond ticks value at tracer start */
  private final long startNanoTicks;
  /** How often should traced threads check clock ticks against the wall clock */
  private final long clockSyncPeriod;
  /** Last time (in nanosecond ticks) the clock was checked for drift */
  private volatile long lastSyncTicks;
  /** Nanosecond offset to counter clock drift */
  private volatile long counterDrift;

  private SystemTimeSource() {
    this(Config.get());
  }

  SystemTimeSource(Config config) {
    startTimeNano = MILLISECONDS.toNanos(System.currentTimeMillis());
    startNanoTicks = System.nanoTime();
    clockSyncPeriod = Math.max(1_000_000L, SECONDS.toNanos(config.getClockSyncPeriod()));
  }

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

  /**
   * Timestamp in nanoseconds.
   *
   * <p>Note: it is not possible to get 'real' nanosecond time. This method uses tracer start time
   * (with millisecond precision) as a reference and applies relative time with nanosecond precision
   * after that. This means time measured with same Tracer in different Spans is relatively correct
   * with nanosecond precision.
   *
   * @return timestamp in nanoseconds
   */
  @Override
  public long getCurrentTimeNanos() {
    long nanoTicks = getNanoTicks();
    long computedNanoTime = startTimeNano + Math.max(0, nanoTicks - startNanoTicks);
    if (nanoTicks - lastSyncTicks >= clockSyncPeriod) {
      long drift = computedNanoTime - MILLISECONDS.toNanos(getCurrentTimeMillis());
      if (Math.abs(drift + counterDrift) >= 1_000_000L) { // allow up to 1ms of drift
        counterDrift = -MILLISECONDS.toNanos(NANOSECONDS.toMillis(drift));
      }
      lastSyncTicks = nanoTicks;
    }
    return computedNanoTime + counterDrift;
  }
}

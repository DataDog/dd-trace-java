package datadog.trace.api.time;

/**
 * Interface to retrieve various time primitives. Limited by the granularity of the underlying
 * platform.
 */
public interface TimeSource {
  /**
   * Returns monotonically increasing ticks from some arbitrary start point. Should only be used to
   * measure durations. Negative numbers and zero are both valid return values
   */
  long getNanoTicks();

  /** Milliseconds since the start of the epoch */
  long getCurrentTimeMillis();

  /** Microseconds since the start of the epoch */
  long getCurrentTimeMicros();

  /** Nanoseconds since the start of the epoch. Valid for the time range 1/1/1970 +/- 270 years */
  long getCurrentTimeNanos();
}

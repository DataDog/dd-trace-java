package datadog.trace.api.metrics;

/** This interface describes a core counter metric. */
public interface CoreCounter {
  /**
   * Get the counter name.
   *
   * @return The counter name.
   */
  String getName();

  /**
   * Get the value and reset the counter.
   *
   * @return The current counter value.
   */
  long getValueAndReset();
}

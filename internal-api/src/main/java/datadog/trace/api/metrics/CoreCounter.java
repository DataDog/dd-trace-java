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
   * Get the counter value.
   *
   * @return The counter value.
   */
  long getValue();

  /**
   * Get the value and reset the counter.
   *
   * @return The current counter value.
   */
  long getValueAndReset();
}

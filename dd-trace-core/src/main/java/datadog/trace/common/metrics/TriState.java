package datadog.trace.common.metrics;

/**
 * TriState is used to represent a three-valued logic: true, false, and unknown. The "integer"
 * values are used for metrics serialization.
 */
public enum TriState {
  UNKNOWN(0),
  TRUE(1),
  FALSE(2);
  public final int serialValue;

  TriState(int serialValue) {
    this.serialValue = serialValue;
  }
}

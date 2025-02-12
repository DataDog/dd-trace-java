package datadog.trace.api.telemetry;

public enum TruncatedType {
  STRING_TOO_LONG(1),
  LIST_MAP_TOO_LARGE(2),
  OBJECT_TOO_DEEP(4);

  private final int value;
  private static final int numValues = RuleType.values().length;

  TruncatedType(int value) {
    this.value = value;
  }

  public int getValue() {
    return this.value;
  }

  public static int getNumValues() {
    return numValues;
  }
}

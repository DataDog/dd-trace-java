package datadog.trace.civisibility.ipc;

public enum TestDataType {
  SKIPPABLE,
  FLAKY;

  private static final TestDataType[] UNIVERSE = TestDataType.values();

  public static TestDataType byOrdinal(int ordinal) {
    return UNIVERSE[ordinal];
  }
}

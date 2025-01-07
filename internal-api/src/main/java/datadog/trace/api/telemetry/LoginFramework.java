package datadog.trace.api.telemetry;

public enum LoginFramework {
  SPRING_SECURITY("spring_security");

  private static final int numValues = LoginFramework.values().length;

  private final String tag;

  LoginFramework(final String tag) {
    this.tag = tag;
  }

  public String getTag() {
    return tag;
  }

  public static int getNumValues() {
    return numValues;
  }
}

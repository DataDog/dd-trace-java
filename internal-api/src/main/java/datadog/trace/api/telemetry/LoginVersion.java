package datadog.trace.api.telemetry;

public enum LoginVersion {
  V1("v1"),
  V2("v2"),
  AUTO(null);

  private final String tag;

  LoginVersion(final String tag) {
    this.tag = tag;
  }

  public String getTag() {
    return tag;
  }

  private static final int numValues = LoginVersion.values().length;

  public static int getNumValues() {
    return numValues;
  }
}

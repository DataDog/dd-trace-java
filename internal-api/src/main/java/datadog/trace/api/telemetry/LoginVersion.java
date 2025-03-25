package datadog.trace.api.telemetry;

public enum LoginVersion {

  /** Login events generated via V1 of ATO */
  V1("v1"),
  /** Login event generated via V2 of ATO */
  V2("v2");

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

package datadog.trace.api;

public enum UserEventTrackingMode {
  DISABLED,
  SAFE,
  EXTENDED;

  public static UserEventTrackingMode fromString(String s) {
    if ("true".equalsIgnoreCase(s) || "1".equals(s) || "safe".equalsIgnoreCase(s)) {
      return SAFE;
    }
    if ("extended".equalsIgnoreCase(s)) {
      return EXTENDED;
    }
    return DISABLED;
  }
}

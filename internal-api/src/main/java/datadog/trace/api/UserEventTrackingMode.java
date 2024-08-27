package datadog.trace.api;

public enum UserEventTrackingMode {
  DISABLED("disabled"),
  SAFE("safe", "true", "1"),
  EXTENDED("extended");

  private final String value;
  private final String[] modes;

  UserEventTrackingMode(final String... modes) {
    value = modes[0];
    this.modes = modes;
  }

  private boolean matches(final String mode) {
    for (final String value : modes) {
      if (value.equalsIgnoreCase(mode)) {
        return true;
      }
    }
    return false;
  }

  public static UserEventTrackingMode fromString(String s) {
    if (SAFE.matches(s)) {
      return SAFE;
    }
    if (EXTENDED.matches(s)) {
      return EXTENDED;
    }
    return DISABLED;
  }

  @Override
  public String toString() {
    return value;
  }
}

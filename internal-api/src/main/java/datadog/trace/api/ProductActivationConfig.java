package datadog.trace.api;

public enum ProductActivationConfig {
  FULLY_ENABLED,
  FULLY_DISABLED,
  ENABLED_INACTIVE;

  public static ProductActivationConfig fromString(String s) {
    if (s.equalsIgnoreCase("true") || s.equals("1")) {
      return FULLY_ENABLED;
    }
    if (s.equalsIgnoreCase("inactive")) {
      return ENABLED_INACTIVE;
    }

    return FULLY_DISABLED;
  }
}

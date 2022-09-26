package datadog.trace.api;

public enum ProductActivationConfig {
  /* The product is initialized, its instrumentation is applied and the logic in the instrumentation
   * advice is applied. It cannot be disabled at runtime */
  FULLY_ENABLED,
  /* Completely disabled. The product is not initialized in any way. It cannot be
   * enabled at runtime */
  FULLY_DISABLED,
  /* The product is initialized, its instrumentation applied, but its logic
   * is disabled to the greatest extent possible. It can be enabled and disabled
   * at runtime though remote config
   */
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

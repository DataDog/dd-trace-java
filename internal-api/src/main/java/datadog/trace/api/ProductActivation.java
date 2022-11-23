package datadog.trace.api;

public enum ProductActivation {
  /**
   * The product is initialized, its instrumentation is applied and the logic in the instrumentation
   * advice is applied. It cannot be disabled at runtime.
   */
  FULLY_ENABLED,
  /**
   * Completely disabled. The product is not initialized in any way. It cannot be enabled at
   * runtime.
   */
  FULLY_DISABLED,
  /**
   * The product is initialized, its instrumentation applied, but its logic is disabled to the
   * greatest extent possible. It can be enabled and disabled at runtime through remote config.
   */
  ENABLED_INACTIVE;

  public static ProductActivation fromString(String s) {
    if ("true".equalsIgnoreCase(s) || "1".equals(s)) {
      return FULLY_ENABLED;
    }
    if ("inactive".equalsIgnoreCase(s)) {
      return ENABLED_INACTIVE;
    }
    return FULLY_DISABLED;
  }
}

package datadog.trace.api;

public enum ProductActivation {
  /**
   * Completely disabled. The product is not initialized in any way. It cannot be enabled at
   * runtime.
   */
  FULLY_DISABLED,
  /**
   * The product is initialized, its instrumentation applied, but its logic is disabled to the
   * greatest extent possible. It can be enabled and disabled at runtime through remote config.
   */
  ENABLED_INACTIVE,
  /**
   * The product is initialized with a bare minimum of its functionality enabled. It cannot be
   * disabled at runtime.
   */
  ENABLED_OPT_OUT,
  /**
   * The product is initialized, its instrumentation is applied and the logic in the instrumentation
   * advice is applied. It cannot be disabled at runtime.
   */
  FULLY_ENABLED;

  public static ProductActivation fromString(String s) {
    if ("true".equalsIgnoreCase(s) || "1".equals(s)) {
      return FULLY_ENABLED;
    }
    if ("inactive".equalsIgnoreCase(s)) {
      return ENABLED_INACTIVE;
    }
    if ("opt-out".equalsIgnoreCase(s)) {
      return ENABLED_OPT_OUT;
    }
    return FULLY_DISABLED;
  }

  public boolean isAtLeast(final ProductActivation value) {
    return value.ordinal() <= ordinal();
  }
}

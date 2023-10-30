package datadog.trace.api;

public enum ProductActivation {
  /**
   * The product is initialized, its instrumentation is applied and the logic in the instrumentation
   * advice is applied. It cannot be disabled at runtime.
   */
  FULLY_ENABLED(2),
  /**
   * Completely disabled. The product is not initialized in any way. It cannot be enabled at
   * runtime.
   */
  FULLY_DISABLED(-1),
  /**
   * The product is initialized, its instrumentation applied, but its logic is disabled to the
   * greatest extent possible. It can be enabled and disabled at runtime through remote config.
   */
  ENABLED_INACTIVE(0),

  /**
   * The product is enabled but in a product-defined lightweight mode, some features are disabled.
   */
  ENABLED_LIGHTWEIGHT(1);

  private final int level;

  ProductActivation(int level) {
    this.level = level;
  }

  /**
   * Product activations are expected to be ordered, so that each level is enables and does not
   * disable features available at the previous level. This allows asserting a minimum level of
   * activation.
   */
  public boolean atLeast(ProductActivation productActivation) {
    return level >= productActivation.level;
  }

  public static ProductActivation fromString(String s) {
    if ("true".equalsIgnoreCase(s) || "1".equals(s)) {
      return FULLY_ENABLED;
    }
    if ("inactive".equalsIgnoreCase(s)) {
      return ENABLED_INACTIVE;
    }
    if ("lightweight".equalsIgnoreCase(s)) {
      return ENABLED_LIGHTWEIGHT;
    }
    return FULLY_DISABLED;
  }
}

package datadog.trace.api.civisibility.config;

public enum LibraryCapability {
  TIA("test_impact_analysis"),
  EFD("early_flake_detection"),
  ATR("auto_test_retries"),
  IMPACTED("impacted_tests"),
  FAIL_FAST("fail_fast_test_order"),
  QUARANTINE("test_management.quarantine"),
  DISABLED("test_management.disable"),
  ATTEMPT_TO_FIX("test_management.attempt_to_fix");

  private final String tag;

  LibraryCapability(String tag) {
    this.tag = tag;
  }

  public String asTag() {
    return "_dd.library_capabilities." + tag;
  }
}

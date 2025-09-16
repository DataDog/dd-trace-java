package datadog.trace.api.civisibility.config;

public enum LibraryCapability {
  TIA("test_impact_analysis", "1"),
  EFD("early_flake_detection", "1"),
  ATR("auto_test_retries", "1"),
  IMPACTED("impacted_tests", "1"),
  FAIL_FAST("fail_fast_test_order", "1"),
  FTR("failed_test_replay", "1"),
  QUARANTINE("test_management.quarantine", "1"),
  DISABLED("test_management.disable", "1"),
  ATTEMPT_TO_FIX("test_management.attempt_to_fix", "5"),
  COV_REPORT_UPLOAD("coverage_report_upload", "1");

  private final String tag;
  private final String version;

  LibraryCapability(String tag, String version) {
    this.tag = tag;
    this.version = version;
  }

  public String asTag() {
    return "_dd.library_capabilities." + tag;
  }

  public String getVersion() {
    return version;
  }
}

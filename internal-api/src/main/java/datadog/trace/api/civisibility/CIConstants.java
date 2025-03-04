package datadog.trace.api.civisibility;

public interface CIConstants {
  /**
   * Indicates that early flakiness detection feature was aborted in a test session because too many
   * test cases were considered new.
   */
  String EFD_ABORT_REASON_FAULTY = "faulty";

  String SELENIUM_BROWSER_DRIVER = "selenium";

  String CI_VISIBILITY_INSTRUMENTATION_NAME = "civisibility";

  String FAIL_FAST_TEST_ORDER = "FAILFAST";

  String CIAPP_TEST_ORIGIN = "ciapp-test";

  /** Tags that change the behaviour of CI Vis features when applied to a test case. */
  interface Tags {
    String ITR_UNSKIPPABLE_TAG = "datadog_itr_unskippable";
    String EFD_DISABLE_TAG = "datadog_efd_disable";
  }
}

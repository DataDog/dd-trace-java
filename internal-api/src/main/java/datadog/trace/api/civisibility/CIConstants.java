package datadog.trace.api.civisibility;

public interface CIConstants {

  /**
   * Maximum length (in characters) of a meta string value sent to the CI Visibility intake; longer
   * values are truncated. Matches the Event Platform (EVP) per-tag-value limit.
   */
  int MAX_META_STRING_VALUE_LENGTH = 5000;

  String SELENIUM_BROWSER_DRIVER = "selenium";

  String FAIL_FAST_TEST_ORDER = "FAILFAST";

  String CIAPP_TEST_ORIGIN = "ciapp-test";

  /** Tags that change the behaviour of CI Vis features when applied to a test case. */
  interface Tags {
    String ITR_UNSKIPPABLE_TAG = "datadog_itr_unskippable";
    String EFD_DISABLE_TAG = "datadog_efd_disable";
  }
}

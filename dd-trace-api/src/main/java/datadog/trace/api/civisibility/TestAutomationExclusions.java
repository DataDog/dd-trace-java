package datadog.trace.api.civisibility;

/** Tags for test frameworks whose test declarations do not expose Java method annotations. */
public final class TestAutomationExclusions {
  public static final String NO_AUTO_RETRY = "datadog_no_auto_retry";
  public static final String PREVENT_FAILURE_SUPPRESSION = "datadog_prevent_failure_suppression";

  private TestAutomationExclusions() {}
}

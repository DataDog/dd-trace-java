package datadog.trace.api.civisibility;

public interface CIConstants {
  /**
   * Indicates that early flakiness detection feature was aborted in a test session because too many
   * test cases were considered new.
   */
  String EFD_ABORT_REASON_FAULTY = "faulty";

  String SELENIUM_BROWSER_DRIVER = "selenium";
}

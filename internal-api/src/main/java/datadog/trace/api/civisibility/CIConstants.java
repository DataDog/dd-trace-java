package datadog.trace.api.civisibility;

public interface CIConstants {
  String TEST_PASS = "pass";
  String TEST_FAIL = "fail";
  String TEST_SKIP = "skip";

  /**
   * Indicates that early flakiness detection feature was aborted in a test session because too many
   * test cases were considered new.
   */
  String EFD_ABORT_REASON_FAULTY = "faulty";
}

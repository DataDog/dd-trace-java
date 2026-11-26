package datadog.trace.api.civisibility.coverage;

/**
 * The parts of Shared: the lines a process ran that are in no test's own coverage, reported once
 * per process. They are split by how tests depend on them.
 */
public enum SharedPart {
  /**
   * Lines of class initializers. A test is credited with the initializer lines of the classes whose
   * other code it ran.
   */
  INIT("init"),
  /** Lines of suite setup. Every test of the suite is credited with them. */
  SUITE_SETUP("suiteSetup"),
  /**
   * Every other line: suite teardown, framework start-up, code that ran with no test context or
   * after its test ended. No test is credited with these lines.
   */
  UNOWNED("unowned");

  private final String payloadName;

  SharedPart(String payloadName) {
    this.payloadName = payloadName;
  }

  /** The name of the part in the coverage payload. */
  public String payloadName() {
    return payloadName;
  }
}

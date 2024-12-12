package datadog.trace.civisibility.domain;

import datadog.trace.api.civisibility.CIVisibility;

public enum InstrumentationType {
  /**
   * Both build system and test frameworks are instrumented. Typically, the build system runs in a
   * parent process, while the tests are executed in child processes.
   */
  BUILD,
  /**
   * Only test frameworks are instrumented. Build system is NOT instrumented, which means the tracer
   * is not aware of any parent or sibling processes that may be running as part of the same build.
   */
  HEADLESS,
  /**
   * Events are created programmatically using <a
   * href="https://docs.datadoghq.com/tests/setup/java/?tab=cloudciprovideragentless#using-manual-testing-api">CI
   * Visibility Manual API</a> ({@link CIVisibility})
   */
  MANUAL_API
}

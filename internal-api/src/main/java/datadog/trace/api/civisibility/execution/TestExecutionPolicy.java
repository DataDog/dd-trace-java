package datadog.trace.api.civisibility.execution;

public interface TestExecutionPolicy extends TestExecutionHistory {

  /**
   * Must be called before the execution is registered by {@link
   * TestExecutionHistory#registerExecution(TestStatus, long)}.
   *
   * @return {@code true} if the next execution of the test will be altered in any way. This method
   *     is used to avoid unnecessary performance penalty: if it returns {@code false},
   *     instrumentation code needed to alter test behavior will be skipped.
   */
  boolean applicable();

  /**
   * Must be called before the execution is registered by {@link
   * TestExecutionHistory#registerExecution(TestStatus, long)}.
   *
   * @return {@code true} if failure of the next execution of the test should not affect build
   *     result
   */
  boolean suppressFailures();
}

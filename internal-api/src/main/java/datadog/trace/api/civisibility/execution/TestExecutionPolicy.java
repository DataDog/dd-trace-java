package datadog.trace.api.civisibility.execution;

public interface TestExecutionPolicy extends TestExecutionHistory {

  /**
   * @return {@code true} if the next execution of the test will be altered in any way. This method
   *     is used to avoid unnecessary performance penalty: if it returns {@code false},
   *     instrumentation code needed to alter test behavior will be skipped.
   */
  boolean applicable();

  /**
   * @return {@code true} if failure of the next execution of the test should not affect build
   *     result
   */
  boolean suppressFailures();

  /**
   * @param successful {@code true} if current test execution passed or was skipped, {@code false}
   *     otherwise
   * @param durationMillis duration of current test execution in milliseconds
   * @return {@code true} if another execution of the same test should be done
   */
  boolean retry(boolean successful, long durationMillis);
}

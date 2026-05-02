package datadog.trace.api.civisibility.execution;

/**
 * Extends {@link TestExecutionTracker} with decision methods that drive retry loops in test
 * framework instrumentations. Implementations are stateful: the return values of {@link
 * #applicable()} and {@link #suppressFailures()} change after each call to {@link
 * #registerExecution(TestStatus, long)}.
 *
 * @see TestExecutionTracker
 */
public interface TestExecutionPolicy extends TestExecutionTracker {

  /**
   * Must be called before the execution is registered by {@link
   * TestExecutionTracker#registerExecution(TestStatus, long)}.
   *
   * @return {@code true} if the next execution of the test will be altered in any way. This method
   *     is used to avoid unnecessary performance penalty: if it returns {@code false},
   *     instrumentation code needed to alter test behavior will be skipped.
   */
  boolean applicable();

  /**
   * Must be called before the execution is registered by {@link
   * TestExecutionTracker#registerExecution(TestStatus, long)}.
   *
   * @return {@code true} if failure of the next execution of the test should not affect build
   *     result
   */
  boolean suppressFailures();

  /**
   * Must be called after the execution is registered by {@link
   * TestExecutionTracker#registerExecution(TestStatus, long)}.
   *
   * @return {@code true} if a passing test result should be converted to failure because the
   *     aggregated results indicate the test should fail the build
   */
  default boolean propagateFailure() {
    return false;
  }
}

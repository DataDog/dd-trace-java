package datadog.trace.api.civisibility;

import javax.annotation.Nullable;

/**
 * This interface represents a test module, i.e. a set of test suites. This typically corresponds to
 * a project module (e.g. Maven submodule or Gradle subproject).
 */
public interface DDTestModule {

  /**
   * Adds an arbitrary tag to the module
   *
   * @param key The name of the tag
   * @param value The value of the tag
   */
  void setTag(String key, Object value);

  /**
   * Marks the module as failed.
   *
   * <p>This method should be used to signal a failure that is not related to a specific suite or a
   * test case, but rather to the module as a whole (e.g. a failure in a setup/teardown logic that
   * is executed once per module). If an individual test suite the module fails, there is no need to
   * explicitly signal it to the module object: the status of the module will reflect individual
   * suite failures automatically.
   *
   * <p>This does not imply the end of module execution, so {@link #end(Long)} method has to be
   * invoked at some point after this one.
   *
   * @param error Optional exception that caused the module to fail
   */
  void setErrorInfo(Throwable error);

  /**
   * Marks the module as skipped.
   *
   * <p>This does not imply the end of module execution, so {@link #end(Long)} method has to be
   * invoked at some point after this one.
   *
   * @param skipReason Optional reason for skipping the module
   */
  void setSkipReason(String skipReason);

  /**
   * Marks the end of module execution.
   *
   * <p>Unless either {@link #setErrorInfo(Throwable)} or {@link #setSkipReason(String)} were
   * invoked prior to calling this method, the status of the module will be calculated based on the
   * statuses of individual test suites that were executed in scope of the module.
   *
   * <p>The method must be called once for each module instance.
   *
   * <p>The call does not have to be made in the same thread where the module was started.
   *
   * @param endTime Optional finish time in microseconds. If {@code null} is supplied, current time
   *     will be assumed
   */
  void end(@Nullable Long endTime);

  /**
   * Marks the start of a new test suite in the module.
   *
   * @param testSuiteName The name of the suite
   * @param testClass Optional class that corresponds to the test suite.
   * @param startTime Optional start time in microseconds. If {@code null} is supplied, current time
   *     will be assumed
   * @return Handle to the test suite instance
   */
  default DDTestSuite testSuiteStart(
      String testSuiteName, @Nullable Class<?> testClass, @Nullable Long startTime) {
    return testSuiteStart(testSuiteName, testClass, startTime, false);
  }

  /**
   * Marks the start of a new test suite in the module.
   *
   * @param testSuiteName The name of the suite
   * @param testClass Optional class that corresponds to the test suite.
   * @param startTime Optional start time in microseconds. If {@code null} is supplied, current time
   *     will be assumed
   * @param parallelized Whether test cases from this suite will be executed concurrently in
   *     multiple threads
   * @return Handle to the test suite instance
   */
  DDTestSuite testSuiteStart(
      String testSuiteName,
      @Nullable Class<?> testClass,
      @Nullable Long startTime,
      boolean parallelized);
}

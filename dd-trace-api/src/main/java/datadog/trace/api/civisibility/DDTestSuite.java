package datadog.trace.api.civisibility;

import java.lang.reflect.Method;
import javax.annotation.Nullable;

/**
 * This interface represents a test suite, i.e. a set of related test cases. Typically, a test suite
 * corresponds to a class that contains a number of test method
 */
public interface DDTestSuite {

  /**
   * Adds an arbitrary tag to the suite
   *
   * @param key The name of the tag
   * @param value The value of the tag
   */
  void setTag(String key, Object value);

  /**
   * Marks the suite as failed.
   *
   * <p>This method should be used to signal a failure that is not related to a specific test case,
   * but rather to the suite as a whole (e.g. a failure in a setup/teardown method whose scope is
   * entire suite). If an individual test case in the suite fails, there is no need to explicitly
   * signal it to the suite object: the status of the suite will reflect individual test case
   * failures automatically.
   *
   * <p>This does not imply the end of suite execution, so {@link #end(Long)} method has to be
   * invoked at some point after this one.
   *
   * @param error Optional exception that caused the suite to fail
   */
  void setErrorInfo(Throwable error);

  /**
   * Marks the suite as skipped.
   *
   * <p>This does not imply the end of suite execution, so {@link #end(Long)} method has to be
   * invoked at some point after this one.
   *
   * @param skipReason Optional reason for skipping the suite
   */
  void setSkipReason(String skipReason);

  /**
   * Marks the end of suite execution.
   *
   * <p>Unless either {@link #setErrorInfo(Throwable)} or {@link #setSkipReason(String)} were
   * invoked prior to calling this method, the status of the suite will be calculated based on the
   * statuses of individual test cases that were executed in scope of the suite.
   *
   * <p>The method must be called once for each suite instance.
   *
   * <p>The call has to be made in the same thread where the suite was started.
   *
   * @param endTime Optional finish time in microseconds. If {@code null} is supplied, current time
   *     will be assumed
   */
  void end(@Nullable Long endTime);

  /**
   * Marks the start of a new test case in the suite.
   *
   * @param testName The name of the test case
   * @param testMethod Optional method that corresponds to the test case
   * @param startTime Optional start time in microseconds. If {@code null} is supplied, current time
   *     will be assumed
   * @return Handle to the test case instance
   */
  DDTest testStart(String testName, @Nullable Method testMethod, @Nullable Long startTime);
}

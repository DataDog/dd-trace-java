package datadog.trace.api.civisibility;

import javax.annotation.Nullable;

/**
 * This interface represent an individual test case. Typically, a test case corresponds to a method
 * that contains the test logic.
 */
public interface DDTest {

  /**
   * Adds an arbitrary tag to the test
   *
   * @param key The name of the tag
   * @param value The value of the tag
   */
  void setTag(String key, Object value);

  /**
   * Marks the test as failed.
   *
   * <p>This does not imply the end of test execution, so {@link #end(Long)} method has to be
   * invoked at some point after this one.
   *
   * @param error Optional exception that caused the test to fail
   */
  void setErrorInfo(@Nullable Throwable error);

  /**
   * Marks the test as skipped.
   *
   * <p>This does not imply the end of test execution, so {@link #end(Long)} method has to be
   * invoked at some point after this one.
   *
   * @param skipReason Optional reason for skipping the test
   */
  void setSkipReason(@Nullable String skipReason);

  /**
   * Marks the end of test execution.
   *
   * <p>Unless either {@link #setErrorInfo(Throwable)} or {@link #setSkipReason(String)} were
   * invoked prior to calling this method, the test is assumed to have finished successfully.
   *
   * <p>The method must be called once for each test instance.
   *
   * <p>The call has to be made in the same thread where the test was started.
   *
   * @param endTime Optional finish time in microseconds. If {@code null} is supplied, current time
   *     will be assumed
   */
  void end(@Nullable Long endTime);
}

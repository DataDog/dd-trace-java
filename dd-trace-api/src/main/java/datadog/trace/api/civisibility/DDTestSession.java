package datadog.trace.api.civisibility;

import javax.annotation.Nullable;

/**
 * This interface represents a test session. Test session usually corresponds to execution of a
 * single test command issued by a user or by a CI script
 */
public interface DDTestSession {

  /**
   * Adds an arbitrary tag to the session
   *
   * @param key The name of the tag
   * @param value The value of the tag
   */
  void setTag(String key, Object value);

  /**
   * Marks the session as failed.
   *
   * <p>This method should be used to signal a failure that is not related to a specific module or a
   * test suite, but rather to the tests execution as a whole (e.g. a failure in a setup/teardown
   * logic that is executed once for the entire project). If an individual module in the project
   * fails, there is no need to explicitly signal it to the session object: the status of the
   * session will reflect individual module failures automatically.
   *
   * <p>This does not imply the end of tests execution, so {@link #end(Long)} method has to be
   * invoked at some point after this one.
   *
   * @param error Optional exception that caused the execution to fail
   */
  void setErrorInfo(Throwable error);

  /**
   * Marks the entire execution as skipped.
   *
   * <p>This does not imply the end of tests execution, so {@link #end(Long)} method has to be
   * invoked at some point after this one.
   *
   * @param skipReason Optional reason for skipping execution
   */
  void setSkipReason(String skipReason);

  /**
   * Marks the end of tests execution.
   *
   * <p>Unless either {@link #setErrorInfo(Throwable)} or {@link #setSkipReason(String)} were
   * invoked prior to calling this method, the status of the execution will be calculated based on
   * the statuses of individual modules that were run in scope of the session.
   *
   * <p>The method must be called once for each session instance.
   *
   * <p>The call does not have to be made in the same thread where the session was started.
   *
   * @param endTime Optional finish time in microseconds. If {@code null} is supplied, current time
   *     will be assumed
   */
  void end(@Nullable Long endTime);

  /**
   * Marks the start of a new module.
   *
   * @param moduleName The name of the module
   * @param startTime Optional start time in microseconds. If {@code null} is supplied, current time
   *     will be assumed
   * @return Handle to the module instance
   */
  DDTestModule testModuleStart(String moduleName, @Nullable Long startTime);
}

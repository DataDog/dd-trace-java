package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import javax.annotation.Nullable;

/** Regular test case execution with no alterations. */
public class Regular implements TestExecutionPolicy {

  public static final TestExecutionPolicy INSTANCE = new Regular();

  private Regular() {}

  @Override
  public void registerExecution(TestStatus status, long durationMillis) {}

  @Override
  public boolean wasLastExecution() {
    return true;
  }

  @Override
  public boolean applicable() {
    return false;
  }

  @Override
  public boolean suppressFailures() {
    return false;
  }

  @Nullable
  @Override
  public RetryReason currentExecutionRetryReason() {
    return null;
  }

  @Override
  public boolean hasFailedAllRetries() {
    return false;
  }

  @Override
  public boolean hasSucceededAllRetries() {
    return false;
  }

  @Override
  public boolean failedTestReplayApplicable() {
    return false;
  }
}

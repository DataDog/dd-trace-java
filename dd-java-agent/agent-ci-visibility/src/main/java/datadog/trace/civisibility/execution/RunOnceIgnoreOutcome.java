package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import javax.annotation.Nullable;

/**
 * Runs a test case once. If it fails - suppresses the failure so that the build status is not
 * affected.
 */
public class RunOnceIgnoreOutcome implements TestExecutionPolicy {

  private boolean testExecuted;

  @Override
  public void registerExecution(TestStatus status, long durationMillis) {
    testExecuted = true;
  }

  @Override
  public boolean wasLastExecution() {
    return testExecuted;
  }

  @Override
  public boolean applicable() {
    return !testExecuted;
  }

  @Override
  public boolean suppressFailures() {
    return true;
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
}

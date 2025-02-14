package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import org.jetbrains.annotations.Nullable;

/**
 * Runs a test case once. If it fails - suppresses the failure so that the build status is not
 * affected.
 */
public class RunOnceIgnoreOutcome implements TestExecutionPolicy {

  private boolean testExecuted;

  @Override
  public boolean applicable() {
    return !testExecuted;
  }

  @Override
  public boolean suppressFailures() {
    return true;
  }

  @Override
  public boolean retry(boolean successful, long durationMillis) {
    testExecuted = true;
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
}

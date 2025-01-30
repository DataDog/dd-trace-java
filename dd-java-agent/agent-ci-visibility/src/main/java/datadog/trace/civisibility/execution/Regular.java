package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import org.jetbrains.annotations.Nullable;

/** Regular test case execution with no alterations. */
public class Regular implements TestExecutionPolicy {

  public static final TestExecutionPolicy INSTANCE = new Regular();

  private Regular() {}

  @Override
  public boolean applicable() {
    return false;
  }

  @Override
  public boolean suppressFailures() {
    return false;
  }

  @Override
  public boolean retry(boolean successful, long durationMillis) {
    return false;
  }

  @Nullable
  @Override
  public RetryReason currentExecutionRetryReason() {
    return null;
  }
}

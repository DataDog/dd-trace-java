package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import datadog.trace.civisibility.config.EarlyFlakeDetectionSettings;
import org.jetbrains.annotations.Nullable;

/** Runs a test case N times (N depends on test duration) regardless of success or failure. */
public class RunNTimes implements TestExecutionPolicy {

  private final EarlyFlakeDetectionSettings earlyFlakeDetectionSettings;
  private final boolean suppressFailures;
  private int executions;
  private int maxExecutions;
  private boolean successfulExecutionSeen;

  public RunNTimes(
      EarlyFlakeDetectionSettings earlyFlakeDetectionSettings, boolean suppressFailures) {
    this.earlyFlakeDetectionSettings = earlyFlakeDetectionSettings;
    this.suppressFailures = suppressFailures;
    this.executions = 0;
    this.maxExecutions = earlyFlakeDetectionSettings.getExecutions(0);
  }

  @Override
  public boolean applicable() {
    return currentExecutionIsNotLast() || suppressFailures();
  }

  private boolean currentExecutionIsNotLast() {
    return executions < maxExecutions - 1;
  }

  @Override
  public boolean suppressFailures() {
    return suppressFailures;
  }

  @Override
  public boolean retry(boolean successful, long durationMillis) {
    successfulExecutionSeen |= successful;
    // adjust maximum retries based on the now known test duration
    int maxExecutionsForGivenDuration = earlyFlakeDetectionSettings.getExecutions(durationMillis);
    maxExecutions = Math.min(maxExecutions, maxExecutionsForGivenDuration);
    return ++executions < maxExecutions;
  }

  @Nullable
  @Override
  public RetryReason currentExecutionRetryReason() {
    return currentExecutionIsRetry() ? RetryReason.efd : null;
  }

  private boolean currentExecutionIsRetry() {
    return executions > 0;
  }

  @Override
  public boolean hasFailedAllRetries() {
    return executions == maxExecutions && !successfulExecutionSeen;
  }
}

package datadog.trace.civisibility.retry;

import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import datadog.trace.civisibility.config.EarlyFlakeDetectionSettings;
import org.jetbrains.annotations.Nullable;

/** Retries a test case N times (N depends on test duration) regardless of success or failure. */
public class RetryNTimes implements TestRetryPolicy {

  private final EarlyFlakeDetectionSettings earlyFlakeDetectionSettings;
  private int executions;
  private int maxExecutions;

  public RetryNTimes(EarlyFlakeDetectionSettings earlyFlakeDetectionSettings) {
    this.earlyFlakeDetectionSettings = earlyFlakeDetectionSettings;
    this.executions = 0;
    this.maxExecutions = earlyFlakeDetectionSettings.getExecutions(0);
  }

  @Override
  public boolean retriesLeft() {
    return executions < maxExecutions - 1;
  }

  @Override
  public boolean suppressFailures() {
    return false;
  }

  @Override
  public boolean retry(boolean successful, long durationMillis) {
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
}

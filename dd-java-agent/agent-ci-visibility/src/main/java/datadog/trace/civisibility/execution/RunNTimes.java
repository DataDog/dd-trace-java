package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import datadog.trace.civisibility.config.EarlyFlakeDetectionSettings;
import org.jetbrains.annotations.Nullable;

/** Runs a test case N times (N depends on test duration) regardless of success or failure. */
public class RunNTimes extends RunNFixedTimes {

  private final EarlyFlakeDetectionSettings earlyFlakeDetectionSettings;

  public RunNTimes(
      EarlyFlakeDetectionSettings earlyFlakeDetectionSettings, boolean suppressFailures) {
    super(earlyFlakeDetectionSettings.getExecutions(0), suppressFailures);
    this.earlyFlakeDetectionSettings = earlyFlakeDetectionSettings;
  }

  @Override
  public boolean retry(boolean successful, long durationMillis) {
    int maxExecutionsForGivenDuration = earlyFlakeDetectionSettings.getExecutions(durationMillis);
    maxExecutions = Math.min(maxExecutions, maxExecutionsForGivenDuration);
    return super.retry(successful, durationMillis);
  }

  @Nullable
  @Override
  public RetryReason currentExecutionRetryReason() {
    return currentExecutionIsRetry() ? RetryReason.efd : null;
  }
}

package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import datadog.trace.civisibility.config.EarlyFlakeDetectionSettings;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Execution policy for dynamic Auto Test Retries (ATR). Instead of a flat per-test retry limit, the
 * number of retries is determined by the duration of the initial attempt, using the same duration
 * buckets as Early Flake Detection. When custom buckets are provided they override the EFD retry
 * settings; otherwise the EFD settings from the backend are used.
 */
public class DynamicAutoTestRetry extends AutoTestRetry {

  private final EarlyFlakeDetectionSettings efdSettings;
  private final int[] customBuckets; // null = use EFD settings
  private boolean maxExecutionsDetermined = false;

  public DynamicAutoTestRetry(
      EarlyFlakeDetectionSettings efdSettings,
      int[] customBuckets,
      boolean suppressFailures,
      AtomicInteger totalRetryCount) {
    super(Integer.MAX_VALUE, suppressFailures, totalRetryCount);
    this.efdSettings = efdSettings;
    this.customBuckets = customBuckets;
  }

  @Override
  public ExecutionOutcome registerExecution(TestStatus status, long durationMillis) {
    if (!maxExecutionsDetermined) {
      maxExecutions = computeMaxExecutions(durationMillis);
      maxExecutionsDetermined = true;
    }
    return super.registerExecution(status, durationMillis);
  }

  private int computeMaxExecutions(long durationMillis) {
    int retries;
    if (customBuckets != null) {
      int index = efdSettings.retryBucketIndexForDuration(durationMillis);
      retries = customBuckets[index];
    } else {
      retries = efdSettings.retriesForDuration(durationMillis);
    }
    return Math.max(1, retries) + 1; // +1 for the initial attempt
  }
}

package datadog.trace.civisibility.execution;

import datadog.trace.civisibility.config.DynamicAutoTestRetrySettings;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Execution policy for dynamic Auto Test Retries (ATR). Instead of a flat per-test retry limit, the
 * number of retries is determined by the duration of the initial attempt.
 */
public class DynamicAutoTestRetry extends AutoTestRetry {

  private final DynamicAutoTestRetrySettings settings;

  public DynamicAutoTestRetry(
      DynamicAutoTestRetrySettings settings,
      boolean suppressFailures,
      AtomicInteger totalRetryCount) {
    super(2, suppressFailures, totalRetryCount);
    this.settings = settings;
  }

  @Override
  protected int maxExecutionsForDuration(long durationMillis) {
    return settings.executionsForDuration(durationMillis);
  }
}

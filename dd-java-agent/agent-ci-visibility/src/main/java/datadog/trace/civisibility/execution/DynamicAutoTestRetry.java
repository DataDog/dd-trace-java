package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.civisibility.config.DynamicAutoTestRetrySettings;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Execution policy for dynamic Auto Test Retries (ATR). Instead of a flat per-test retry limit, the
 * number of retries is determined by the duration of the initial attempt.
 */
public class DynamicAutoTestRetry extends AbstractAutoTestRetry {

  private final DynamicAutoTestRetrySettings settings;
  private int maxExecutions = 2;
  private boolean maxExecutionsDetermined;

  public DynamicAutoTestRetry(
      DynamicAutoTestRetrySettings settings,
      boolean suppressFailures,
      AtomicInteger totalRetryCount) {
    super(suppressFailures, totalRetryCount);
    this.settings = settings;
  }

  @Override
  public ExecutionOutcome registerExecution(TestStatus status, long durationMillis) {
    if (!maxExecutionsDetermined) {
      int retries = Math.max(1, settings.retriesForDuration(durationMillis));
      // Duration buckets count retries only; the execution limit also includes the initial attempt.
      maxExecutions = retries + 1;
      maxExecutionsDetermined = true;
    }
    return super.registerExecution(status, durationMillis);
  }

  @Override
  protected int maxExecutions() {
    return maxExecutions;
  }
}

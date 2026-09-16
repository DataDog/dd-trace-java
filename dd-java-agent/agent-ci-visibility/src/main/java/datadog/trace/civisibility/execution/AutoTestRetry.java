package datadog.trace.civisibility.execution;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Execution policy for Auto Test Retries (ATR). Retries a test case if it failed, up to a maximum
 * number of times. Stops retrying as soon as the test passes.
 */
public class AutoTestRetry extends AbstractAutoTestRetry {

  private final int maxExecutions;

  public AutoTestRetry(int maxExecutions, boolean suppressFailures, AtomicInteger totalRetryCount) {
    super(suppressFailures, totalRetryCount);
    this.maxExecutions = maxExecutions;
  }

  @Override
  protected int maxExecutions() {
    return maxExecutions;
  }
}

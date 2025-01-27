package datadog.trace.api.civisibility.retry;

import javax.annotation.Nullable;

public interface TestRetryPolicy {
  boolean retriesLeft();

  boolean suppressFailures();

  boolean retry(boolean successful, long duration);

  boolean currentExecutionIsRetry();

  /**
   * Returns retry reason for current execution (will be {@code null} if current execution is not a
   * retry)
   */
  @Nullable
  String currentExecutionRetryReason();
}

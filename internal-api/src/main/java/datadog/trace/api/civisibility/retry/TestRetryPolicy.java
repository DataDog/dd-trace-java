package datadog.trace.api.civisibility.retry;

import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import javax.annotation.Nullable;

public interface TestRetryPolicy {

  /** @return {@code true} if failure of this test should not affect the build result */
  boolean suppressFailures();

  /**
   * @return {@code true} if this test can be retried. Current execution is NOT taken into account
   */
  boolean retriesLeft();

  /**
   * @param successful {@code true} if test passed or was skipped, {@code false} otherwise
   * @param durationMillis test duration in milliseconds
   * @return {@code true} if this test should be retried
   */
  boolean retry(boolean successful, long durationMillis);

  /**
   * Returns retry reason for current execution (will be {@code null} if current execution is not a
   * retry)
   */
  @Nullable
  RetryReason currentExecutionRetryReason();
}

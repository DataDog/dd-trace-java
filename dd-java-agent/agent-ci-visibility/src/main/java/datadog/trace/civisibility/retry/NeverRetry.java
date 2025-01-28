package datadog.trace.civisibility.retry;

import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import org.jetbrains.annotations.Nullable;

public class NeverRetry implements TestRetryPolicy {

  public static final TestRetryPolicy INSTANCE = new NeverRetry();

  private NeverRetry() {}

  @Override
  public boolean retriesLeft() {
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

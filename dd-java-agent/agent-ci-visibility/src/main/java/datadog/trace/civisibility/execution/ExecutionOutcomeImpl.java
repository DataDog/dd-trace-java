package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.TestExecutionHistory;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import org.jetbrains.annotations.Nullable;

class ExecutionOutcomeImpl implements TestExecutionHistory.ExecutionOutcome {

  private final boolean failureSuppressed;
  private final boolean lastExecution;
  private final boolean failedAllRetries;
  private final boolean succeededAllRetries;
  private final RetryReason retryReason;

  ExecutionOutcomeImpl(
      boolean failureSuppressed,
      boolean lastExecution,
      boolean failedAllRetries,
      boolean succeededAllRetries,
      RetryReason retryReason) {
    this.failureSuppressed = failureSuppressed;
    this.lastExecution = lastExecution;
    this.failedAllRetries = failedAllRetries;
    this.succeededAllRetries = succeededAllRetries;
    this.retryReason = retryReason;
  }

  @Override
  public boolean failureSuppressed() {
    return failureSuppressed;
  }

  @Override
  public boolean lastExecution() {
    return lastExecution;
  }

  @Override
  public boolean failedAllRetries() {
    return failedAllRetries;
  }

  @Override
  public boolean succeededAllRetries() {
    return succeededAllRetries;
  }

  @Nullable
  @Override
  public RetryReason retryReason() {
    return retryReason;
  }
}

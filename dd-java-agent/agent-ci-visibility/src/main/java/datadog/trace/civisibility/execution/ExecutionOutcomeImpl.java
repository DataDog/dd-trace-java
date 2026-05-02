package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.ExecutionAggregation;
import datadog.trace.api.civisibility.execution.TestExecutionTracker;
import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import javax.annotation.Nullable;

class ExecutionOutcomeImpl implements TestExecutionTracker.ExecutionOutcome {

  private final boolean failureSuppressed;
  private final boolean lastExecution;
  private final ExecutionAggregation aggregation;
  private final RetryReason retryReason;
  private final TestStatus finalStatus;

  ExecutionOutcomeImpl(
      boolean failureSuppressed,
      boolean lastExecution,
      ExecutionAggregation aggregation,
      RetryReason retryReason,
      TestStatus finalStatus) {
    this.failureSuppressed = failureSuppressed;
    this.lastExecution = lastExecution;
    this.aggregation = aggregation;
    this.retryReason = retryReason;
    this.finalStatus = finalStatus;
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
  public ExecutionAggregation aggregation() {
    return aggregation;
  }

  @Nullable
  @Override
  public RetryReason retryReason() {
    return retryReason;
  }

  @Nullable
  @Override
  public TestStatus finalStatus() {
    return finalStatus;
  }
}

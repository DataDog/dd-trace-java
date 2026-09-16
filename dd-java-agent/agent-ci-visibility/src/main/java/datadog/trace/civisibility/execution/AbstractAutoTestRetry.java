package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.ExecutionAggregation;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressFBWarnings(
    value = {"AT_NONATOMIC_OPERATIONS_ON_SHARED_VARIABLE"},
    justification =
        "TestExecutionPolicy instances are confined to a single thread and are not meant to be thread-safe")
abstract class AbstractAutoTestRetry implements TestExecutionPolicy {

  private final boolean suppressFailures;
  private final AtomicInteger totalRetryCount;
  private int executions;
  private ExecutionAggregation results = ExecutionAggregation.NONE;

  protected AbstractAutoTestRetry(boolean suppressFailures, AtomicInteger totalRetryCount) {
    this.suppressFailures = suppressFailures;
    this.totalRetryCount = totalRetryCount;
  }

  /** Returns the maximum number of executions, including the initial attempt. */
  protected abstract int maxExecutions();

  @Override
  public ExecutionOutcome registerExecution(TestStatus status, long durationMillis) {
    ++executions;
    results = results.withExecution(status);
    if (executions > 1) {
      totalRetryCount.incrementAndGet();
    }

    boolean lastExecution = !retriesLeft();
    boolean retry = executions > 1;
    boolean failureSuppressed = status == TestStatus.fail && (!lastExecution || suppressFailures);
    TestStatus finalStatus = null;
    if (lastExecution) {
      finalStatus = failureSuppressed ? TestStatus.pass : status;
    }

    return new ExecutionOutcomeImpl(
        failureSuppressed, lastExecution, results, retry ? RetryReason.atr : null, finalStatus);
  }

  private boolean retriesLeft() {
    return executions < maxExecutions()
        && results != ExecutionAggregation.ONLY_PASSED
        && results != ExecutionAggregation.MIXED;
  }

  @Override
  public boolean applicable() {
    return retriesLeft();
  }

  @Override
  public boolean suppressFailures() {
    return executions + 1 < maxExecutions() || suppressFailures;
  }

  @Override
  public boolean failedTestReplayApplicable() {
    return true;
  }
}

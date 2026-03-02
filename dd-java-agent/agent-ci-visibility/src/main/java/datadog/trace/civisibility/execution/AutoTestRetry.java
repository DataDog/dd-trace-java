package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.ExecutionAggregation;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Execution policy for Auto Test Retries (ATR). Retries a test case if it failed, up to a maximum
 * number of times. Stops retrying as soon as the test passes.
 */
@SuppressFBWarnings(
    value = {"AT_NONATOMIC_OPERATIONS_ON_SHARED_VARIABLE"},
    justification =
        "TestExecutionPolicy instances are confined to a single thread and are not meant to be thread-safe")
public class AutoTestRetry implements TestExecutionPolicy {

  private final int maxExecutions;
  private final boolean suppressFailures;
  private int executions;
  private ExecutionAggregation results;

  /** Total retry counter that is shared by all auto test retry policies */
  private final AtomicInteger totalRetryCount;

  public AutoTestRetry(int maxExecutions, boolean suppressFailures, AtomicInteger totalRetryCount) {
    this.maxExecutions = maxExecutions;
    this.suppressFailures = suppressFailures;
    this.totalRetryCount = totalRetryCount;
    this.executions = 0;
    this.results = ExecutionAggregation.NONE;
  }

  @Override
  public ExecutionOutcome registerExecution(TestStatus status, long durationMillis) {
    ++executions;
    results = results.withExecution(status);
    if (executions > 1) {
      totalRetryCount.incrementAndGet();
    }

    boolean lastExecution = !retriesLeft();
    boolean retry = executions > 1; // first execution is not a retry
    boolean failureSuppressed = status == TestStatus.fail && (!lastExecution || suppressFailures);
    TestStatus finalStatus = null;
    if (lastExecution) {
      // final status is always the last status reported (or pass if a failure is suppressed)
      finalStatus = failureSuppressed ? TestStatus.pass : status;
    }

    return new ExecutionOutcomeImpl(
        failureSuppressed, lastExecution, results, retry ? RetryReason.atr : null, finalStatus);
  }

  private boolean retriesLeft() {
    return executions < maxExecutions
        && results != ExecutionAggregation.ONLY_PASSED
        && results != ExecutionAggregation.MIXED;
  }

  @Override
  public boolean applicable() {
    return retriesLeft();
  }

  @Override
  public boolean suppressFailures() {
    // do not suppress failures for last execution (unless flag to suppress all failures is set);
    // the +1 is because this method is called _before_ subsequent execution is registered
    return executions + 1 < maxExecutions || suppressFailures;
  }

  @Override
  public boolean failedTestReplayApplicable() {
    return true;
  }
}

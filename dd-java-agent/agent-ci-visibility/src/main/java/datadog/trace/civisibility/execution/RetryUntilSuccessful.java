package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.concurrent.atomic.AtomicInteger;

/** Retries a test case if it failed, up to a maximum number of times. */
@SuppressFBWarnings(
    value = {"AT_NONATOMIC_OPERATIONS_ON_SHARED_VARIABLE", "AT_STALE_THREAD_WRITE_OF_PRIMITIVE"},
    justification =
        "TestExecutionPolicy instances are confined to a single thread and are not meant to be thread-safe")
public class RetryUntilSuccessful implements TestExecutionPolicy {

  private final int maxExecutions;
  private final boolean suppressFailures;
  private int executions;
  private boolean successfulExecutionSeen;

  /** Total retry counter that is shared by all retry until successful policies (currently ATR) */
  private final AtomicInteger totalRetryCount;

  public RetryUntilSuccessful(
      int maxExecutions, boolean suppressFailures, AtomicInteger totalRetryCount) {
    this.maxExecutions = maxExecutions;
    this.suppressFailures = suppressFailures;
    this.totalRetryCount = totalRetryCount;
    this.executions = 0;
  }

  @Override
  public ExecutionOutcome registerExecution(TestStatus status, long durationMillis) {
    ++executions;
    successfulExecutionSeen |= (status != TestStatus.fail);
    if (executions > 1) {
      totalRetryCount.incrementAndGet();
    }

    boolean lastExecution = !retriesLeft();
    boolean retry = executions > 1; // first execution is not a retry
    return new ExecutionOutcomeImpl(
        status == TestStatus.fail && (!lastExecution || suppressFailures),
        lastExecution,
        lastExecution && !successfulExecutionSeen,
        false,
        retry ? RetryReason.atr : null);
  }

  private boolean retriesLeft() {
    return !successfulExecutionSeen && executions < maxExecutions;
  }

  @Override
  public boolean applicable() {
    // executions must always be registered, therefore consider it applicable as long as there are
    // retries left
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

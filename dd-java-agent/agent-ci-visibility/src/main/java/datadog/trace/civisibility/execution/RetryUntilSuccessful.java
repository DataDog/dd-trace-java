package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Retries a test case if it failed, up to a maximum number of times. */
public class RetryUntilSuccessful implements TestExecutionPolicy {

  private final int maxExecutions;
  private final boolean suppressFailures;
  private final AtomicInteger executions = new AtomicInteger(0);
  private final AtomicBoolean successfulExecutionSeen = new AtomicBoolean(false);

  /** Total retry counter that is shared by all retry until successful policies (currently ATR) */
  private final AtomicInteger totalRetryCount;

  public RetryUntilSuccessful(
      int maxExecutions, boolean suppressFailures, AtomicInteger totalRetryCount) {
    this.maxExecutions = maxExecutions;
    this.suppressFailures = suppressFailures;
    this.totalRetryCount = totalRetryCount;
  }

  @Override
  public ExecutionOutcome registerExecution(TestStatus status, long durationMillis) {
    int execs = executions.incrementAndGet();
    boolean success = successfulExecutionSeen.get() | (status != TestStatus.fail);
    successfulExecutionSeen.set(success);
    if (execs > 1) {
      totalRetryCount.incrementAndGet();
    }

    boolean lastExecution = !retriesLeft();
    boolean retry = execs > 1; // first execution is not a retry
    return new ExecutionOutcomeImpl(
        status == TestStatus.fail && (!lastExecution || suppressFailures),
        lastExecution,
        lastExecution && !success,
        false,
        retry ? RetryReason.atr : null);
  }

  private boolean retriesLeft() {
    return !successfulExecutionSeen.get() && executions.get() < maxExecutions;
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
    return executions.get() + 1 < maxExecutions || suppressFailures;
  }

  @Override
  public boolean failedTestReplayApplicable() {
    return true;
  }
}

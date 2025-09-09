package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.TestExecutionHistory;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.execution.TestStatus;
import datadog.trace.api.civisibility.telemetry.tag.RetryReason;
import javax.annotation.Nullable;

/** Regular test case execution with no alterations. */
public class Regular implements TestExecutionPolicy {

  public static final TestExecutionPolicy INSTANCE = new Regular();

  private Regular() {}

  @Override
  public ExecutionOutcome registerExecution(TestStatus status, long durationMillis) {
    return RegularExecutionOutcome.INSTANCE;
  }

  @Override
  public boolean applicable() {
    return false;
  }

  @Override
  public boolean suppressFailures() {
    return false;
  }

  private static final class RegularExecutionOutcome
      implements TestExecutionHistory.ExecutionOutcome {

    static final TestExecutionHistory.ExecutionOutcome INSTANCE = new RegularExecutionOutcome();

    private RegularExecutionOutcome() {}

    @Override
    public boolean failureSuppressed() {
      return false;
    }

    @Override
    public boolean lastExecution() {
      return false;
    }

    @Override
    public boolean failedAllRetries() {
      return false;
    }

    @Override
    public boolean succeededAllRetries() {
      return false;
    }

    @Nullable
    @Override
    public RetryReason retryReason() {
      return null;
    }
  }

  @Override
  public boolean failedTestReplayApplicable() {
    return false;
  }
}

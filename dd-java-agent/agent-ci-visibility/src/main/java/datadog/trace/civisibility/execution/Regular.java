package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.execution.TestStatus;

/** Regular test case execution with no alterations. */
public class Regular implements TestExecutionPolicy {

  public static final TestExecutionPolicy INSTANCE = new Regular();

  private Regular() {}

  @Override
  public ExecutionOutcome registerExecution(TestStatus status, long durationMillis) {
    return new ExecutionOutcomeImpl(false, true, false, false, null, status);
  }

  @Override
  public boolean applicable() {
    return false;
  }

  @Override
  public boolean suppressFailures() {
    return false;
  }

  @Override
  public boolean failedTestReplayApplicable() {
    return false;
  }
}

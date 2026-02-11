package datadog.trace.civisibility.execution;

import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.execution.TestStatus;

/**
 * Runs a test case once. If it fails - suppresses the failure so that the build status is not
 * affected.
 */
public class RunOnceIgnoreOutcome implements TestExecutionPolicy {

  private boolean testExecuted;

  @Override
  public ExecutionOutcome registerExecution(TestStatus status, long durationMillis) {
    testExecuted = true;
    return new ExecutionOutcomeImpl(
        status == TestStatus.fail,
        testExecuted,
        false,
        false,
        null,
        status == TestStatus.fail ? TestStatus.pass : status);
  }

  @Override
  public boolean applicable() {
    return !testExecuted;
  }

  @Override
  public boolean suppressFailures() {
    return true;
  }

  @Override
  public boolean failedTestReplayApplicable() {
    return false;
  }
}

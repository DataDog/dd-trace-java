package datadog.trace.instrumentation.karate;

import com.intuit.karate.core.Scenario;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import java.util.Collection;

public class ExecutionContext {

  private final TestExecutionPolicy executionPolicy;
  private boolean suppressFailures;
  private boolean failed;
  private long startTimestamp;

  public ExecutionContext(TestExecutionPolicy executionPolicy) {
    this.executionPolicy = executionPolicy;
  }

  public void setStartTimestamp(long startTimestamp) {
    this.startTimestamp = startTimestamp;
  }

  public long getStartTimestamp() {
    return startTimestamp;
  }

  public void setFailed(boolean failed) {
    this.failed = failed;
  }

  public boolean getAndResetFailed() {
    boolean failed = this.failed;
    this.failed = false;
    return failed;
  }

  public void setSuppressFailures(boolean suppressFailures) {
    this.suppressFailures = suppressFailures;
  }

  public boolean getAndResetSuppressFailures() {
    boolean suppressFailures = this.suppressFailures;
    this.suppressFailures = false;
    return suppressFailures;
  }

  public TestExecutionPolicy getExecutionPolicy() {
    return executionPolicy;
  }

  public static ExecutionContext create(Scenario scenario) {
    TestIdentifier testIdentifier = KarateUtils.toTestIdentifier(scenario);
    Collection<String> testTags = scenario.getTagsEffective().getTagKeys();
    return new ExecutionContext(
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.executionPolicy(
            testIdentifier, TestSourceData.UNKNOWN, testTags));
  }
}

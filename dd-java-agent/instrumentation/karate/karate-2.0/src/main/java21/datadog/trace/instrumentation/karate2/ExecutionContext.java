package datadog.trace.instrumentation.karate2;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import io.karatelabs.gherkin.Scenario;
import java.util.Collection;

public class ExecutionContext {

  private final TestExecutionPolicy executionPolicy;
  private boolean suppressFailures;
  private Throwable suppressedError;

  public ExecutionContext(TestExecutionPolicy executionPolicy) {
    this.executionPolicy = executionPolicy;
  }

  public void setSuppressFailures(boolean suppressFailures) {
    this.suppressFailures = suppressFailures;
  }

  public boolean shouldSuppressFailures() {
    return suppressFailures;
  }

  public TestExecutionPolicy getExecutionPolicy() {
    return executionPolicy;
  }

  /**
   * Karate v2 {@code StepResult} is immutable (no {@code setFailedReason}/{@code setErrorIgnored}),
   * so a failure that was suppressed for retry purposes cannot be carried on the replacement step.
   * We stash it here instead, so the tracing listener can still report the failure to CI
   * Visibility.
   */
  public void setSuppressedError(Throwable suppressedError) {
    if (this.suppressedError == null) {
      this.suppressedError = suppressedError;
    }
  }

  public Throwable getAndClearSuppressedError() {
    Throwable suppressedError = this.suppressedError;
    this.suppressedError = null;
    return suppressedError;
  }

  public static ExecutionContext create(Scenario scenario) {
    TestIdentifier testIdentifier = KarateUtils.toTestIdentifier(scenario);
    Collection<String> testTags = KarateUtils.getCategories(scenario.getTagsEffective());
    return new ExecutionContext(
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.executionPolicy(
            testIdentifier, TestSourceData.UNKNOWN, testTags));
  }
}

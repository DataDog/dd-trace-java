package datadog.trace.instrumentation.karate;

import com.intuit.karate.core.Scenario;
import com.intuit.karate.core.ScenarioResult;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import java.util.Collection;

public class ExecutionContext {

  private final TestExecutionPolicy executionPolicy;
  private boolean suppressFailures;
  private ScenarioResult originalResult;
  private ScenarioResult finalResult;

  public ExecutionContext(TestExecutionPolicy executionPolicy) {
    this.executionPolicy = executionPolicy;
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

  /**
   * Records the canonical result for a retried scenario. Used to check whether the result must be
   * substituted with the result of the final attempt.
   */
  public void recordResultSubstitution(ScenarioResult originalResult, ScenarioResult finalResult) {
    this.originalResult = originalResult;
    this.finalResult = finalResult;
  }

  /**
   * Returns the result that should replace the supplied one when it is added to the {@code
   * FeatureResult}, or {@code null} if no substitution applies. This repoints the canonical
   * scenario result to the final retry attempt without mutating {@code ScenarioRuntime#result},
   * which is not JEP 500 compliant.
   */
  public ScenarioResult substituteResult(ScenarioResult result) {
    return result == originalResult ? finalResult : null;
  }

  public static ExecutionContext create(Scenario scenario) {
    TestIdentifier testIdentifier = KarateUtils.toTestIdentifier(scenario);
    Collection<String> testTags = scenario.getTagsEffective().getTagKeys();
    return new ExecutionContext(
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.executionPolicy(
            testIdentifier, TestSourceData.UNKNOWN, testTags));
  }
}

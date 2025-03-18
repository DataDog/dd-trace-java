package datadog.trace.instrumentation.testng.execution;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.execution.TestExecutionHistory;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.instrumentation.testng.TestEventsHandlerHolder;
import datadog.trace.instrumentation.testng.TestNGUtils;
import java.util.Collection;
import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;

public class RetryAnalyzer implements IRetryAnalyzer {

  private volatile TestExecutionPolicy executionPolicy;

  @Override
  public boolean retry(ITestResult result) {
    if (TestEventsHandlerHolder.TEST_EVENTS_HANDLER == null) {
      return false;
    }
    if (executionPolicy == null) {
      synchronized (this) {
        if (executionPolicy == null) {
          TestIdentifier testIdentifier = TestNGUtils.toTestIdentifier(result);
          TestSourceData testSourceData = TestNGUtils.toTestSourceData(result);
          Collection<String> testTags = TestNGUtils.getGroups(result);
          executionPolicy =
              TestEventsHandlerHolder.TEST_EVENTS_HANDLER.executionPolicy(
                  testIdentifier, testSourceData, testTags);
        }
      }
    }
    return executionPolicy.retry(
        result.isSuccess(), result.getEndMillis() - result.getStartMillis());
  }

  public TestExecutionHistory getExecutionHistory() {
    return executionPolicy;
  }

  public boolean suppressFailures() {
    return executionPolicy != null && executionPolicy.suppressFailures();
  }
}

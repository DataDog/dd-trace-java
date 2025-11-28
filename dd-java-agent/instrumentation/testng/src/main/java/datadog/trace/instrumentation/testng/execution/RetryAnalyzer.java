package datadog.trace.instrumentation.testng.execution;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.execution.TestExecutionHistory;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.instrumentation.testng.TestEventsHandlerHolder;
import datadog.trace.instrumentation.testng.TestNGUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collection;
import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;

public class RetryAnalyzer implements IRetryAnalyzer {

  private volatile TestExecutionPolicy executionPolicy;

  @SuppressFBWarnings(value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE", justification = "the field is confined to a single thread")
  private boolean suppressFailures;

  public void createExecutionPolicy(ITestResult result) {
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
  }

  @Override
  public boolean retry(ITestResult result) {
    if (TestEventsHandlerHolder.TEST_EVENTS_HANDLER == null) {
      return false;
    }
    createExecutionPolicy(result);
    return executionPolicy.applicable();
  }

  public void setSuppressFailures(ITestResult result) {
    createExecutionPolicy(result);
    suppressFailures = executionPolicy.suppressFailures();
  }

  public boolean getAndResetSuppressFailures() {
    boolean suppressFailures = this.suppressFailures;
    this.suppressFailures = false;
    return suppressFailures;
  }

  public TestExecutionHistory getExecutionHistory() {
    return executionPolicy;
  }
}

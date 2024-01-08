package datadog.trace.instrumentation.testng.retry;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.instrumentation.testng.TestEventsHandlerHolder;
import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;

public class RetryAnalyzer implements IRetryAnalyzer {

  private volatile TestRetryPolicy retryPolicy;

  @Override
  public boolean retry(ITestResult result) {
    if (TestEventsHandlerHolder.TEST_EVENTS_HANDLER == null) {
      return false;
    }
    if (retryPolicy == null) {
      synchronized (this) {
        if (retryPolicy == null) {
          String testSuiteName = result.getInstanceName();
          String testName =
              (result.getName() != null) ? result.getName() : result.getMethod().getMethodName();
          TestIdentifier testIdentifier = new TestIdentifier(testSuiteName, testName, null, null);
          retryPolicy = TestEventsHandlerHolder.TEST_EVENTS_HANDLER.retryPolicy(testIdentifier);
        }
      }
    }
    return retryPolicy.retry(result.isSuccess());
  }
}

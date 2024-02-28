package datadog.trace.instrumentation.testng.retry;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.instrumentation.testng.TestEventsHandlerHolder;
import datadog.trace.instrumentation.testng.TestNGUtils;
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
          TestIdentifier testIdentifier = TestNGUtils.toTestIdentifier(result);
          retryPolicy = TestEventsHandlerHolder.TEST_EVENTS_HANDLER.retryPolicy(testIdentifier);
        }
      }
    }
    return retryPolicy.retry(result.isSuccess(), result.getEndMillis() - result.getStartMillis());
  }

  public boolean currentExecutionIsRetry() {
    return retryPolicy != null && retryPolicy.currentExecutionIsRetry();
  }
}

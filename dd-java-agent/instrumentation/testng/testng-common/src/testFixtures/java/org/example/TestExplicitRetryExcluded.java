package org.example;

import datadog.trace.api.civisibility.NoAutoRetry;
import datadog.trace.api.civisibility.PreventFailureSuppression;
import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;
import org.testng.annotations.Test;

public class TestExplicitRetryExcluded {
  public static int executions;

  @Test(retryAnalyzer = RetryOnce.class)
  @NoAutoRetry
  @PreventFailureSuppression
  public void explicit_retry() {
    if (++executions == 1) {
      throw new AssertionError("first framework attempt");
    }
  }

  public static class RetryOnce implements IRetryAnalyzer {
    private int retries;

    public boolean retry(ITestResult result) {
      return retries++ == 0;
    }
  }
}

package org.example;

import datadog.trace.api.civisibility.NoAutoRetry;
import org.testng.annotations.Test;

@Test(retryAnalyzer = TestExplicitRetryExcluded.RetryOnce.class)
public class TestClassRetryExcluded {
  public static int executions;

  @NoAutoRetry
  public void class_retry() {
    if (++executions == 1) {
      throw new AssertionError("first framework attempt");
    }
  }
}

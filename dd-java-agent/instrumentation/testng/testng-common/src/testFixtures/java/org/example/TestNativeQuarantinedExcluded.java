package org.example;

import datadog.trace.api.civisibility.NoAutoRetry;
import org.testng.annotations.Test;

public class TestNativeQuarantinedExcluded {
  public static int executions;

  @Test(retryAnalyzer = TestExplicitRetryExcluded.RetryOnce.class)
  @NoAutoRetry
  public void native_quarantined() {
    executions++;
    throw new AssertionError("native failure remains quarantined");
  }
}

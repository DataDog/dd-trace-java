package org.example;

import datadog.trace.api.civisibility.NoAutoRetry;
import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;
import org.testng.annotations.Test;

public class TestFactoryRetryExcluded {
  public static int executions;

  @Test(retryAnalyzer = ConfiguredRetry.class)
  @NoAutoRetry
  public void factory_retry() {
    if (++executions == 1) {
      throw new AssertionError("first framework attempt");
    }
  }

  public static class ConfiguredRetry implements IRetryAnalyzer {
    private final String configuration;
    private int retries;

    public ConfiguredRetry(String configuration) {
      this.configuration = configuration;
    }

    public boolean retry(ITestResult result) {
      if (!"configured".equals(configuration)) {
        throw new AssertionError("missing object factory configuration");
      }
      return retries++ == 0;
    }
  }
}

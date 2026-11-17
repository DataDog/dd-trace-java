package org.example;

import datadog.trace.api.civisibility.NoAutoRetry;
import datadog.trace.api.civisibility.PreventFailureSuppression;
import org.testng.annotations.Test;

public class TestAutomationExcluded {
  public static int executions;

  @Test
  @NoAutoRetry
  @PreventFailureSuppression
  public void protected_failure() {
    executions++;
    throw new AssertionError("protected failure");
  }
}

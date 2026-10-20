package org.example;

import org.testng.annotations.Test;

public class TestTransformedDisabledRetry {
  public static int executions;

  @Test
  public void transformed_disabled_retry() {
    ++executions;
    throw new AssertionError("explicitly disabled framework retries");
  }
}

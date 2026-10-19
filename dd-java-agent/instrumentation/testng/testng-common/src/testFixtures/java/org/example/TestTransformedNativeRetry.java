package org.example;

import org.testng.annotations.Test;

public class TestTransformedNativeRetry {
  public static int executions;

  @Test
  public void transformed_native_retry() {
    if (++executions == 1) {
      throw new AssertionError("first framework attempt");
    }
  }
}

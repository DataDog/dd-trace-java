package org.example;

import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

public class TestFailAndThenSucceed {

  private int executions;

  @Test
  public void test_fail_and_then_succeed() {
    assertTrue(++executions >= 2);
  }
}

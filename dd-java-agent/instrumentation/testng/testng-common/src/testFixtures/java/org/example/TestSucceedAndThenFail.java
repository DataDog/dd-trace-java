package org.example;

import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

public class TestSucceedAndThenFail {

  private int executions;

  @Test
  public void test_succeed_and_then_fail() {
    assertTrue(++executions < 2);
  }
}

package org.example;

import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

public class TestFailedWithSuccessPercentage {

  private int i = 0;

  @Test(successPercentage = 60, invocationCount = 5)
  public void test_failed_with_success_percentage() {
    i++;
    if (i == 1 || i == 2) {
      assertTrue(false);
    } else {
      assertTrue(true);
    }
  }
}

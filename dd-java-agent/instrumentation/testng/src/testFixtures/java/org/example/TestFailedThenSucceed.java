package org.example;

import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

public class TestFailedThenSucceed {

  private int retry;

  @Test
  public void test_failed() {
    assertTrue(++retry >= 3);
  }
}

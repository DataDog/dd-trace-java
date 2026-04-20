package org.example;

import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

public class TestFailedAndSucceed {

  @Test
  public void test_succeed() {
    assertTrue(true);
  }

  @Test
  public void test_failed() {
    assertTrue(false);
  }

  @Test
  public void test_another_succeed() {
    assertTrue(true);
  }
}

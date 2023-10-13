package org.example;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

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

package org.example;

import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public class TestFailedAfter {
  @After
  public void tearDown() {
    throw new RuntimeException("testcase teardown failed");
  }

  @Test
  public void test_succeed() {
    assertTrue(true);
  }

  @Test
  public void another_test_succeed() {
    assertTrue(true);
  }
}

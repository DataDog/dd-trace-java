package org.example;

import static org.junit.Assert.assertTrue;

import org.junit.AfterClass;
import org.junit.Test;

public class TestFailedAfterClass {
  @AfterClass
  public static void tearDown() {
    throw new RuntimeException("suite teardown failed");
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

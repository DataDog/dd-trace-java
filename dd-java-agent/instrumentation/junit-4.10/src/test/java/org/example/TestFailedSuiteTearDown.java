package org.example;

import static org.junit.Assert.assertTrue;

import org.junit.AfterClass;
import org.junit.Test;

public class TestFailedSuiteTearDown {

  @AfterClass
  public static void suiteTearDown() {
    throw new RuntimeException("suite tear down failed");
  }

  @Test
  public void test_succeed() {
    assertTrue(true);
  }

  @Test
  public void test_another_succeed() {
    assertTrue(true);
  }
}

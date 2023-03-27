package org.example;

import static org.testng.Assert.assertTrue;

import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

public class TestFailedSuiteTearDown {

  @AfterClass
  public void tearDown() {
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

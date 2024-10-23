package org.example;

import static org.testng.Assert.assertTrue;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class TestFailedSuiteSetup {

  @BeforeClass
  public void setup() {
    throw new RuntimeException("suite set up failed");
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

package org.example;

import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

public class TestFailedSuiteSetup {

  @BeforeClass
  public static void suiteSetup() {
    throw new RuntimeException("suite set up failed");
  }

  @Test
  public void test_succeed() {
    assertTrue(true);
  }
}

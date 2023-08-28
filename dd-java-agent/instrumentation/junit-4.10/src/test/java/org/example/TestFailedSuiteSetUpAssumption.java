package org.example;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.BeforeClass;
import org.junit.Test;

public class TestFailedSuiteSetUpAssumption {

  @BeforeClass
  public static void suiteSetup() {
    assumeTrue(1 > 2);
  }

  @Test
  public void test_succeed() {
    assertTrue(true);
  }
}

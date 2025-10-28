package org.example;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class TestSuiteSetUpAssumption {

  @BeforeAll
  public static void suiteSetup() {
    assumeTrue(1 > 2);
  }

  @Test
  public void test_succeed() {
    assertTrue(true);
  }
}

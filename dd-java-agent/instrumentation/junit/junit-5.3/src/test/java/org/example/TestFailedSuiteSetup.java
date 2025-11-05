package org.example;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class TestFailedSuiteSetup {

  @BeforeAll
  public static void suiteSetup() {
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

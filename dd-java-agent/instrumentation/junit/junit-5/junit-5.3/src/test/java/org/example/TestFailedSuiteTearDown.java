package org.example;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

public class TestFailedSuiteTearDown {

  @AfterAll
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

package org.example;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class TestFailedAndSucceed {

  @Test
  public void test_succeed() {
    assertTrue(true);
  }

  @Test
  public void test_failed() {
    assertTrue(false);
  }

  @Test
  public void test_another_succeed() {
    assertTrue(true);
  }
}

package org.example;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class TestFailedAfterEach {

  @AfterEach
  public void tearDown() {
    throw new RuntimeException("testcase teardown failed");
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

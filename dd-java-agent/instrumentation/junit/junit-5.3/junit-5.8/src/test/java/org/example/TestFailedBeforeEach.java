package org.example;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestFailedBeforeEach {

  @BeforeEach
  public void setUp() {
    throw new RuntimeException("testcase setup failed");
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

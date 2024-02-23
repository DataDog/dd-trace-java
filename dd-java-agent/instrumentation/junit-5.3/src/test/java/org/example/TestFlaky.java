package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class TestFlaky {

  public static int TEST_EXECUTIONS_IDX = 0;

  @Test
  public void test_flake() {
    assertEquals(0, TEST_EXECUTIONS_IDX++ % 2);
  }
}

package org.example;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class TestSucceedSlow {

  @Test
  public void test_succeed() throws InterruptedException {
    Thread.sleep(1100);
    assertTrue(true);
  }
}

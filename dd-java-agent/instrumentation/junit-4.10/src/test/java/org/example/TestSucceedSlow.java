package org.example;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TestSucceedSlow {

  @Test
  public void test_succeed() throws InterruptedException {
    Thread.sleep(1100);
    assertTrue(true);
  }
}

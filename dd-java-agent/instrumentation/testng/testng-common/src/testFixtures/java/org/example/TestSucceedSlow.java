package org.example;

import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

public class TestSucceedSlow {

  @Test
  public void test_succeed() throws InterruptedException {
    Thread.sleep(1100);
    assertTrue(true);
  }
}

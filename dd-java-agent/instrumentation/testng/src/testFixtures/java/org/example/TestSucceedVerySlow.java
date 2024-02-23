package org.example;

import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

public class TestSucceedVerySlow {

  @Test
  public void test_succeed() throws InterruptedException {
    Thread.sleep(2100);
    assertTrue(true);
  }
}

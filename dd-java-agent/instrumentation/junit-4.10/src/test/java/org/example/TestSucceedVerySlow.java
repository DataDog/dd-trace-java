package org.example;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TestSucceedVerySlow {

  @Test
  public void test_succeed() throws InterruptedException {
    Thread.sleep(2100);
    assertTrue(true);
  }
}

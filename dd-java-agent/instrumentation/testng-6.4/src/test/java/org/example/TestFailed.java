package org.example;

import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

public class TestFailed {

  @Test
  public void test_failed() {
    assertTrue(false);
  }
}

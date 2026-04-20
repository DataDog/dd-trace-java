package org.example;

import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

public class TestSucceedMultiple {

  @Test
  public void test_succeed() {
    assertTrue(true);
  }

  @Test
  public void test_succeed_another() {
    assertTrue(true);
  }
}

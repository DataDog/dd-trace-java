package com.example;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TestFailed {

  @Test
  public void test_failed() {
    assertTrue(Calculator.add(2, 2) == 22);
  }

  @Test
  public void test_another_failed() {
    assertTrue(Calculator.add(1,1) == 11);
  }

}

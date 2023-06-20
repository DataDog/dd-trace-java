package org.example;

import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

public class TestSucceedThreeCases {

  @Test
  public void test_succeed_a() {
    assertTrue(true);
  }

  @Test
  public void test_succeed_b() {
    assertTrue(true);
  }

  @Test
  public void test_succeed_c() {
    assertTrue(true);
  }
}

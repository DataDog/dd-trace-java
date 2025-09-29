package org.example;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.OrderWith;

@OrderWith(ReverseAlphanumeric.class)
public class TestOrderer {
  @Test
  public void test_succeed_1() {
    assertTrue(true);
  }

  @Test
  public void test_succeed_2() {
    assertTrue(true);
  }

  @Test
  public void test_succeed_3() {
    assertTrue(true);
  }
}

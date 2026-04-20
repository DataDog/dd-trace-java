package org.example;

import org.junit.Test;

public class TestSucceedExpectedException {

  @Test(expected = IllegalArgumentException.class)
  public void test_succeed() {
    throw new IllegalArgumentException("expected");
  }
}

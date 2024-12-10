package org.example;

import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class TestFailedBefore {
  @Before
  public void setup() {
    throw new RuntimeException("testcase setup failed");
  }

  @Test
  public void test_succeed() {
    assertTrue(true);
  }

  @Test
  public void another_test_succeed() {
    assertTrue(true);
  }
}

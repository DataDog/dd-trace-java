package org.example;

import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TestSucceedBeforeAfter {
  @Before
  public void setup() {}

  @After
  public void tearDown() {}

  @Test
  public void test_succeed() {
    assertTrue(true);
  }

  @Test
  public void another_test_succeed() {
    assertTrue(true);
  }
}

package org.example;

import static org.junit.Assert.assertTrue;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestSucceedBeforeClassAfterClass {
  @BeforeClass
  public static void setup() {}

  @AfterClass
  public static void tearDown() {}

  @Test
  public void test_succeed() {
    assertTrue(true);
  }

  @Test
  public void another_test_succeed() {
    assertTrue(true);
  }
}

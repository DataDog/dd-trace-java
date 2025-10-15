package org.example;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.*;

public class TestSucceedBeforeAllAfterAll {

  @BeforeAll
  public static void setUp() {}

  @AfterAll
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

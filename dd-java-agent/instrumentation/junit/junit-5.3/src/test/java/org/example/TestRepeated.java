package org.example;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.RepeatedTest;

public class TestRepeated {

  @RepeatedTest(2)
  public void test_repeated() {
    assertTrue(true);
  }
}

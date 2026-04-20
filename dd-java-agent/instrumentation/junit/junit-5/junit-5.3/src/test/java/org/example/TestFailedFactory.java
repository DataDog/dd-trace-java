package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import org.junit.jupiter.api.DynamicTest;

public class TestFailedFactory {

  @org.junit.jupiter.api.TestFactory
  public Iterable<DynamicTest> test_factory() {
    return Arrays.asList(
        DynamicTest.dynamicTest("dynamic_test_succeed", () -> assertEquals(0, 0)),
        DynamicTest.dynamicTest("dynamic_test_failed", () -> assertEquals(2, 1 + 1 + 1)));
  }
}

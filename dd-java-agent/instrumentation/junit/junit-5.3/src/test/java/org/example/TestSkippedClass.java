package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Disabled("Ignore reason in class")
public class TestSkippedClass {

  @Test
  public void test_case_skipped() {}

  @org.junit.jupiter.api.TestFactory
  public Iterable<DynamicTest> test_factory_skipped() {
    return Arrays.asList(
        DynamicTest.dynamicTest("dynamic_test_succeed", () -> assertEquals(0, 0)),
        DynamicTest.dynamicTest("dynamic_test_succeed", () -> assertEquals(2, 1 + 1)));
  }

  @ParameterizedTest
  @MethodSource("parameters")
  public void test_parameterized_skipped(
      final int first, final int second, final int expectedSum, final String message) {
    final int actualSum = first + second;
    assertEquals(expectedSum, actualSum);
    assertNotNull(message);
  }

  static List<Arguments> parameters() {
    return Arrays.asList(
        () -> new Object[] {0, 0, "0", "some:\"parameter\""},
        () -> new Object[] {1, 1, 2, "some:\"parameter\""});
  }

  @RepeatedTest(2)
  public void test_repeated_skipped() {
    assertTrue(true);
  }
}

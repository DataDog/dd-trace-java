package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class TestParameterized {

  static List<Arguments> parameters() {
    return Arrays.asList(
        () -> new Object[] {0, 0, "0", "some:\"parameter\""},
        () -> new Object[] {1, 1, 2, "some:\"parameter\""});
  }

  @ParameterizedTest
  @MethodSource("parameters")
  public void test_parameterized(
      final int first, final int second, final int expectedSum, final String message) {
    final int actualSum = first + second;
    assertEquals(expectedSum, actualSum);
    assertNotNull(message);
  }
}

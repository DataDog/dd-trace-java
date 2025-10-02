package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class TestFailedParameterized {

  static List<Arguments> parameters() {
    return Arrays.asList(() -> new Object[] {0, 0, 42}, () -> new Object[] {1, 1, 42});
  }

  @ParameterizedTest
  @MethodSource("parameters")
  public void test_failed_parameterized(final int first, final int second, final int expectedSum) {
    final int actualSum = first + second;
    assertEquals(expectedSum, actualSum);
  }
}

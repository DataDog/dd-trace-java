package com.datadog.debugger.el.predicates;

import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.el.Value;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class NumericPredicateTest {
  @ParameterizedTest
  @MethodSource("compareArguments")
  void compare(Value<?> left, Value<?> right, int expected) {
    assertEquals(expected, NumericPredicate.compare(left, right));
  }

  @Test
  void validate() {
    assertDoesNotThrow(() -> NumericPredicate.validate(Value.undefinedValue()));
    assertDoesNotThrow(() -> NumericPredicate.validate(Value.nullValue()));
    assertThrows(IllegalArgumentException.class, () -> NumericPredicate.validate(Value.of("a")));
  }

  private static Stream<Arguments> compareArguments() {
    return Stream.of(
        Arguments.of(Value.nullValue(), Value.nullValue(), 0),
        Arguments.of(Value.nullValue(), Value.undefinedValue(), 0),
        Arguments.of(Value.undefinedValue(), Value.nullValue(), 0),
        Arguments.of(Value.nullValue(), Value.of(1), -1),
        Arguments.of(Value.undefinedValue(), Value.of(1), -1),
        Arguments.of(Value.of(1), Value.nullValue(), 1),
        Arguments.of(Value.of(1), Value.undefinedValue(), 1),
        Arguments.of(Value.of(1), Value.of(1), 0),
        Arguments.of(Value.of(1), Value.of(2), -1),
        Arguments.of(Value.of(2), Value.of(1), 1),
        Arguments.of(Value.of(Double.NaN), Value.of(1), 1),
        Arguments.of(Value.of(1), Value.of(Double.NaN), -1),
        Arguments.of(Value.of(Float.NaN), Value.of(1), 1),
        Arguments.of(Value.of(1), Value.of(Float.NaN), -1));
  }
}

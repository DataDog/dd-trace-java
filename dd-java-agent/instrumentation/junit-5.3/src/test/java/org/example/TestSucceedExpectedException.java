package org.example;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class TestSucceedExpectedException {

  @Test
  public void test_succeed() {
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          throw new IllegalArgumentException("expected exception");
        });
  }
}

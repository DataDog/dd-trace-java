package org.example;

import org.junit.jupiter.api.Test;

public class TestError {

  @Test
  public void test_error() {
    throw new IllegalArgumentException("This exception is an example");
  }
}

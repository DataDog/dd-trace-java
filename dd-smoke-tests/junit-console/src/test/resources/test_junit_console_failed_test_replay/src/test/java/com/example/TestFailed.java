package com.example;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

public class TestFailed {

  @Test
  public void test_failed() {
    assertTrue(Calculator.add(2, 2) == 22);
  }

}

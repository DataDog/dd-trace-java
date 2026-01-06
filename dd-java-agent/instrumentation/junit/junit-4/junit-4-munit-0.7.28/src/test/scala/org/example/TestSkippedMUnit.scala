package org.example

import munit.FunSuite

class TestSkippedMUnit extends FunSuite {
  test("Calculator.add".ignore) {
    assertEquals(1 + 2, 3)
  }
}

package org.example

import munit.FunSuite

@munit.IgnoreSuite
class TestSkippedSuiteMUnit extends FunSuite {
  test("Calculator.add".ignore) {
    assertEquals(1 + 2, 3)
  }

  test("Calculator.subtract".ignore) {
    assertEquals(5 - 3, 18)
  }
}

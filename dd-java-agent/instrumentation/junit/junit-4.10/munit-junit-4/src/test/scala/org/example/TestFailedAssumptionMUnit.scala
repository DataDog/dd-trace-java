package org.example

import munit.FunSuite

class TestFailedAssumptionMUnit extends FunSuite {
  test("Calculator.add") {
    assume(2 + 2 == 5, "this test always assumes the wrong thing")
    assertEquals(1 + 2, 3)
  }
}

package org.example

import munit.FunSuite

class TestSucceedMUnit extends FunSuite {
  test("Calculator.add") {
    assertEquals(1 + 2, 3)
  }
}

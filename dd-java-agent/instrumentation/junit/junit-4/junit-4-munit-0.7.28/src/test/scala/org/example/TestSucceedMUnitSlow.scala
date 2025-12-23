package org.example

import munit.FunSuite

class TestSucceedMUnitSlow extends FunSuite {
  test("Calculator.add") {
    Thread.sleep(1100)
    assertEquals(1 + 2, 3)
  }
}

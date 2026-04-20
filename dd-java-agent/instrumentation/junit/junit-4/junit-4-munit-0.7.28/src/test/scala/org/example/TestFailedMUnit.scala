package org.example

import munit.FunSuite

class TestFailedMUnit extends FunSuite {
  test("Calculator.add".tag(new munit.Tag("myTag"))) {
    assertEquals(2 + 2, 22)
  }
}

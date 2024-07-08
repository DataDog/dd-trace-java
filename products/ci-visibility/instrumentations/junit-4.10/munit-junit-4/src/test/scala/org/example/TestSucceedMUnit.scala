package org.example

import munit.FunSuite

class TestSucceedMUnit extends FunSuite {
  test("Calculator.add".tag(new munit.Tag("myTag"))) {
    assertEquals(1 + 2, 3)
  }
}

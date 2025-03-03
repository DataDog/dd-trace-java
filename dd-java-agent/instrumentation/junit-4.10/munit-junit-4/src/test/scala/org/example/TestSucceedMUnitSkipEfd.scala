package org.example

import munit.FunSuite

class TestSucceedMUnitSkipEfd extends FunSuite {
  test("Calculator.add".tag(new munit.Tag("datadog_skip_efd"))) {
    assertEquals(1 + 2, 3)
  }
}

package org.example

import munit.FunSuite

class TestFailedThenSucceedMUnit extends FunSuite {

  var TEST_EXECUTIONS_COUNT = 0

  test("Calculator.add".tag(new munit.Tag("myTag"))) {
    TEST_EXECUTIONS_COUNT += 1
    assertEquals(TEST_EXECUTIONS_COUNT, 3)
  }
}

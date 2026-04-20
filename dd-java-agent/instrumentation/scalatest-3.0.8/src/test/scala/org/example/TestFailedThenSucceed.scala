package org.example

import org.scalatest.funsuite.AnyFunSuite

class TestFailedThenSucceed extends AnyFunSuite {

  var TEST_EXECUTIONS_COUNT = 0

  test("Example.add adds two numbers") {
    TEST_EXECUTIONS_COUNT += 1
    assert(TEST_EXECUTIONS_COUNT === 3)
  }
}

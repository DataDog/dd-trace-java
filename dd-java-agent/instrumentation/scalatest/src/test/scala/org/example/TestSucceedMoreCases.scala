package org.example

import org.scalatest.funsuite.AnyFunSuite

class TestSucceedMoreCases extends AnyFunSuite {
  test("Example.add adds two numbers") {
    assert(2 + 2 === 4)
  }

  test("Example.subtract subtracts two numbers") {
    assert(2 - 2 === 0)
  }

  test("Example.multiply multiplies two numbers") {
    assert(2 * 3 === 6)
  }
}

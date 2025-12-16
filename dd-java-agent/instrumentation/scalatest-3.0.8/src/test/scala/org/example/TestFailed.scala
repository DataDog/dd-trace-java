package org.example

import org.scalatest.funsuite.AnyFunSuite

class TestFailed extends AnyFunSuite {
  test("Example.add adds two numbers") {
    assert(2 + 2 === 42)
  }
}

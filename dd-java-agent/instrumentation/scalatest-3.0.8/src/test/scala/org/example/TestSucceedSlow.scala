package org.example

import org.scalatest.funsuite.AnyFunSuite

class TestSucceedSlow extends AnyFunSuite {
  test("Example.add adds two numbers") {
    Thread.sleep(1100)
    assert(2 + 2 === 4)
  }
}

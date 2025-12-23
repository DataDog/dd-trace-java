package org.example

import org.scalatest.funsuite.AnyFunSuite

class TestIgnoredCanceled extends AnyFunSuite {
  test("Example.add adds two numbers") {
    assume(2 + 2 === 22)
  }
}

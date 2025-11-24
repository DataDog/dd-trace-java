package org.example

import weaver._

object TestFailedExceptionPure extends FunSuite {
  test("pure exception test") {
    expect(1 == 1)
    throw new RuntimeException("Exception inside test.")
  }
}

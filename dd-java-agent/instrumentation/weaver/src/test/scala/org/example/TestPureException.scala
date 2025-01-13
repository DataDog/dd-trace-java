package org.example

import weaver._

object TestPureException extends FunSuite {
  test("pure exception test") {
    expect(1 == 1)
    throw new RuntimeException("Exception inside test.")
  }
}

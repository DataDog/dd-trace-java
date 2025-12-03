package org.example

import weaver._

object TestFailedPure extends FunSuite {
  test("pure test fails") {
    expect(2 == 1)
  }
}

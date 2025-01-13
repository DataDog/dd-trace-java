package org.example

import weaver._

object TestPureSucceeded extends FunSuite {
  test("pure test succeeds") {
    expect(1 == 1)
  }
}

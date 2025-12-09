package org.example

import weaver._

object TestSucceedPure extends FunSuite {
  test("pure test succeeds") {
    expect(1 == 1)
  }
}

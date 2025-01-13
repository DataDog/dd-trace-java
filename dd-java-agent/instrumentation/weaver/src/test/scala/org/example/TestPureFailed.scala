package org.example

import weaver._

object TestPureFailed extends FunSuite {
  test("pure test fails") {
    expect(2 == 1)
  }
}

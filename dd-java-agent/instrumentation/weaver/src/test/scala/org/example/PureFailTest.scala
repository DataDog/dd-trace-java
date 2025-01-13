package org.example

import weaver._

object PureFailTest extends FunSuite {
  test("pure test fails") {
    expect(2 == 1)
  }
}

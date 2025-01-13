package org.example

import weaver._

object PureSucceedTest extends FunSuite {
  test("pure test succeeds") {
    expect(1 == 1)
  }
}

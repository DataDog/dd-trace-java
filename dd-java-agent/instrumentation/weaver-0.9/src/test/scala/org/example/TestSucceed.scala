package org.example

import cats.effect._
import weaver._

object TestSucceed extends SimpleIOSuite {
  test("test succeeds") {
    for {
      x <- IO.delay(1)
      y <- IO.delay(1)
    } yield expect(x == y)
  }
}

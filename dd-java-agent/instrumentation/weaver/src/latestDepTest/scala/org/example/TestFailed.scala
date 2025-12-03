package org.example

import cats.effect._
import weaver._

object TestFailed extends SimpleIOSuite {
  test("test fails") {
    for {
      x <- IO.delay(1)
      y <- IO.delay(2)
    } yield expect(x == y)
  }
}

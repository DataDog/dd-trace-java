package org.example

import cats.effect._
import weaver._

object TestCanceled extends SimpleIOSuite {
  test("test canceled") {
    for {
      _ <- cancel("cancel reason")
    } yield expect(1 == 1)
  }
}

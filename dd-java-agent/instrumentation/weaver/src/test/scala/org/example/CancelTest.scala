package org.example

import cats.effect._
import weaver._

object CancelTest extends SimpleIOSuite {
  test("test cancelled") {
    for {
      _ <- cancel("cancel reason")
    } yield expect(1 == 1)
  }
}

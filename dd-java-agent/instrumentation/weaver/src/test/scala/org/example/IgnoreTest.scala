package org.example

import cats.effect._
import weaver._

object IgnoreTest extends SimpleIOSuite {
  test("test ignored") {
    for {
      _ <- ignore("ignore reason")
    } yield expect(1 == 1)
  }
}

package org.example

import cats.effect._
import weaver._

object TestSucceedSuiteResource extends IOSuite {

  override type Res = Int

  override def sharedResource: Resource[IO, Res] = Resource.pure[IO, Res](42)

  test("Test 1 Shared Suite Resource") { res =>
    IO(expect(res == 42))
  }

  test("Test 2 Shared Suite Resource") { res =>
    IO(expect(res != 45))
  }
}

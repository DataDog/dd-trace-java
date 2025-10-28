package org.example

import cats.effect.*
import weaver.*

object MyResource extends GlobalResource {
  override def sharedResources(global: GlobalWrite): Resource[IO, Unit] =
    baseResources.flatMap(global.putR(_))

  def baseResources: Resource[IO, String] = Resource.pure[IO, String]("hello world!")

  def sharedResourceOrFallback(read: GlobalRead): Resource[IO, String] =
    read.getR[String]().flatMap {
      case Some(value) => Resource.eval(IO(value))
      case None        => baseResources
    }
}

class TestSucceedGlobalResourceClass(global: GlobalRead) extends IOSuite {

  import MyResource.*

  override type Res = String

  override def sharedResource: Resource[IO, String] = sharedResourceOrFallback(global)

  test("Test Global Resource") { sharedString =>
    IO(expect(sharedString == "hello world!"))
  }
}

object TestSucceedGlobalResource
    extends TestSucceedGlobalResourceClass(
      global = GlobalResourceF.Read.empty[IO](IO.asyncForIO)
    ) {}

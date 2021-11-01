import cats.data.{Kleisli, OptionT}
import cats.effect.{ConcurrentEffect, ContextShift, IO, Timer}
import datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint
import datadog.trace.agent.test.base.{HttpServer, HttpServerTest}
import groovy.lang.Closure
import org.http4s.Method.GET
import org.http4s.dsl.io._
import org.http4s._
import org.http4s.HttpRoutes
import org.http4s.syntax.all._
import org.http4s.headers.Location
import org.http4s.server.Server
import org.http4s.blaze.server.BlazeServerBuilder

import java.net.URI
import scala.concurrent.ExecutionContext.global

class BlazeHttpTestWebServer extends HttpServer {
  var port: Int = 0

  implicit val contextShift: ContextShift[IO]         = IO.contextShift(global)
  implicit val timer: Timer[IO]                       = IO.timer(global)
  implicit val concurrentEffect: ConcurrentEffect[IO] = IO.ioConcurrentEffect

  var server: Server  = _
  var finalizer: IO[Unit] = _

  object SomeQueryParamMatcher extends QueryParamDecoderMatcher[String]("some")

  override def start(): Unit = synchronized {
    val resource = BlazeServerBuilder[IO]
      .withHttpApp(withController)
      .bindAny("localhost")
      .resource

    val materializedServer = resource.allocated.unsafeRunSync()
    server = materializedServer._1
    finalizer = materializedServer._2

    port = server.address.getPort
    println(s"BlazeHttpTestWebServer started on port: $port")
  }

  private val routes: Kleisli[IO, Request[IO], Response[IO]] = HttpRoutes
    .of[IO] {
      case GET -> Root / "success" =>
        Ok(ServerEndpoint.SUCCESS.getBody)
      case GET -> Root / "redirect" =>
        Found(
          Location(
            Uri.fromString(ServerEndpoint.REDIRECT.getBody).toOption.get
          )
        )
      case GET -> Root / "exception" =>
        throw new Exception(ServerEndpoint.EXCEPTION.getBody)
      case GET -> Root / "error-status" =>
        InternalServerError(ServerEndpoint.ERROR.getBody)
      case GET -> Root / "query" :? SomeQueryParamMatcher(some) =>
        Ok("some=" + some)
      case GET -> Root / "encoded_query" :? SomeQueryParamMatcher(some) =>
        Ok("some=" + some)
      case GET -> Root / "encoded path query" :? SomeQueryParamMatcher(
            some
          ) =>
        Ok("some=" + some)
      case GET -> Root / "path" / id / "param" =>
        Ok(s"$id")
    }
    .orNotFound

  private val withController = HttpApp
    .apply[IO] { request =>
      val uri      = request.uri
      val endpoint = HttpServerTest.ServerEndpoint.forPath(uri.path.toString())
      IO.delay {
        val response = HttpServerTest.controller(endpoint, new Closure[Response[IO]](()) {
          def doCall(): Response[IO] = {
            val res = routes(request).unsafeRunSync()
            res
          }
        })
        response
      }
    }

  override def stop(): Unit = synchronized {
    finalizer.unsafeRunSync()
  }

  override def address(): URI = new URI("http://localhost:" + port + "/")
}

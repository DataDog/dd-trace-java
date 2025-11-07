package datadog.trace.instrumentation.play23.test.server

import datadog.appsec.api.blocking.Blocking
import datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint._
import datadog.trace.agent.test.base.{HttpServer, HttpServerTest}
import play.api.mvc.{Action, Handler, Results}
import play.api.test.{FakeApplication, TestServer}
import play.core.server.NettyServer

import java.net.{InetSocketAddress, URI}

class SyncServer extends HttpServer {
  val routes: PartialFunction[(String, String), Handler] = {
    case ("GET", "/success") =>
      Action { request =>
        HttpServerTest.controller(
          SUCCESS,
          new ControllerClosureAdapter(
            Results.Status(SUCCESS.getStatus).apply(SUCCESS.getBody)
          )
        )
      }
    case ("GET", "/forwarded") =>
      Action { request =>
        HttpServerTest.controller(
          FORWARDED,
          new ControllerClosureAdapter(
            Results.Status(FORWARDED.getStatus).apply(request.remoteAddress)
          )
        )
      }
    case ("GET", "/redirect") =>
      Action { request =>
        HttpServerTest.controller(
          REDIRECT,
          new ControllerClosureAdapter(
            Results.Redirect(REDIRECT.getBody, REDIRECT.getStatus)
          )
        )
      }
    case ("GET", "/query") =>
      Action { request =>
        HttpServerTest.controller(
          QUERY_PARAM,
          new ControllerClosureAdapter(
            Results.Status(QUERY_PARAM.getStatus).apply(QUERY_PARAM.getBody)
          )
        )
      }
    case ("GET", "/encoded_query") =>
      Action { request =>
        HttpServerTest.controller(
          QUERY_ENCODED_QUERY,
          new ControllerClosureAdapter(
            Results
              .Status(QUERY_ENCODED_QUERY.getStatus)
              .apply(QUERY_ENCODED_QUERY.getBody)
          )
        )
      }
    case ("GET", "/encoded%20path%20query") =>
      Action { request =>
        HttpServerTest.controller(
          QUERY_ENCODED_BOTH,
          new ControllerClosureAdapter(
            Results
              .Status(QUERY_ENCODED_BOTH.getStatus)
              .apply(QUERY_ENCODED_BOTH.getBody)
          )
        )
      }
    case ("GET", "/error-status") =>
      Action { request =>
        HttpServerTest.controller(
          ERROR,
          new ControllerClosureAdapter(
            Results.Status(ERROR.getStatus).apply(ERROR.getBody)
          )
        )
      }
    case ("GET", "/exception") =>
      Action {
        HttpServerTest.controller(
          EXCEPTION,
          BlockClosureAdapter {
            throw new Exception(EXCEPTION.getBody)
          }
        )
      }

    case ("GET", "/user-block") =>
      Action {
        HttpServerTest.controller(
          USER_BLOCK,
          BlockClosureAdapter {
            Blocking.forUser("user-to-block").blockIfMatch()
            Results.Ok("should never be reached")
          }
        )
      }
  }

  private val server: TestServer = TestServer(
    0,
    FakeApplication(withGlobal = Some(new Settings()), withRoutes = routes)
  )
  private var port: Int = 0

  override def start(): Unit = {
    server.start()
    val serverField = server.getClass.getDeclaredField("server")
    serverField.setAccessible(true)
    val nettyServer = serverField.get(server).asInstanceOf[NettyServer]
    port = nettyServer.HTTP.get._2.getLocalAddress
      .asInstanceOf[InetSocketAddress]
      .getPort
  }

  override def stop(): Unit = {
    server.stop()
  }

  override def address(): URI = {
    new URI("http://localhost:" + port + "/")
  }
}

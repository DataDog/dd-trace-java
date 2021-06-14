import java.net.URI

import akka.actor.{ActorLogging, ActorSystem, Props}
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint
import datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint._
import datadog.trace.agent.test.base.{HttpServer, HttpServerTest}
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.Trace
import groovy.lang.Closure
import spray.can.Http
import spray.http.HttpResponse
import spray.routing.{HttpServiceActor, RequestContext}

import scala.concurrent.Await
import scala.concurrent.duration._

class SprayHttpTestWebServer extends HttpServer {
  var port: Int = 0
  implicit var system: ActorSystem = null
  implicit val timeout: Timeout = Timeout(5.seconds)

  override def start(): Unit = synchronized {
    start(PortUtils.randomOpenPort())
  }
  def start(_port: Int): Unit = synchronized {
    if (null == system) {
      import scala.concurrent.duration._
      system = ActorSystem("on-spray-can")
      port = _port
      val service = system.actorOf(Props[ServiceActor], "test-service")
      Await.result(
        IO(Http) ? Http.Bind(service, interface = "localhost", port = port),
        10 seconds
      )
      println(s"SprayHttpTestWebServer started on port: $port")
    }
  }

  override def stop(): Unit = synchronized {
    system.shutdown()
    system = null
  }

  override def address(): URI = new URI("http://localhost:" + port + "/")
}

class ServiceActor extends HttpServiceActor with ActorLogging {
  def receive = runRoute {
    path(SUCCESS.rawPath()) {
      get { ctx: RequestContext =>
        HttpServerTest.controller(
          SUCCESS,
          new ControllerHttpResponseToClosureAdapter(ctx, SUCCESS)
        )
      }
    } ~
      path(FORWARDED.rawPath()) {
        get { ctx: RequestContext =>
          HttpServerTest.controller(
            FORWARDED,
            new ControllerHttpResponseToClosureAdapter(ctx, FORWARDED)
          )
        }
      } ~
      path(REDIRECT.rawPath()) {
        get { ctx: RequestContext =>
          HttpServerTest.controller(
            REDIRECT,
            new ControllerHttpRedirectResponseToClosureAdapter(ctx)
          )
        }
      } ~
      path(QUERY_PARAM.rawPath()) {
        get { ctx: RequestContext =>
          HttpServerTest.controller(
            QUERY_PARAM,
            new ControllerHttpResponseToClosureAdapter(ctx, QUERY_PARAM)
          )
        }
      } ~
      path(ERROR.rawPath()) {
        get { ctx: RequestContext =>
          HttpServerTest.controller(
            ERROR,
            new ControllerHttpResponseToClosureAdapter(ctx, ERROR)
          )
        }
      } ~
      path(EXCEPTION.rawPath()) {
        get { ctx: RequestContext =>
          HttpServerTest.controller(
            EXCEPTION,
            new BlockClosureAdapter(() => {
              throw new Exception(EXCEPTION.getBody)
            })
          )
        }
      }
    // actually not found path:
    //      path(NOT_FOUND.rawPath()) {
    //        get { ctx =>
    //          ctx.complete(NOT_FOUND.getBody)
    //        }
    //      }
    // todo: spray.routing.FutureDirectives
    //      path(SUCCESS.rawPath()) {
    //        import scala.concurrent.ExecutionContext.Implicits.global
    //        onSuccess(Future {
    //          "sucess-future"
    //        }) {
    //          result => complete(result)
    //        }
    //      } ~
  }
  @Trace
  def tracedMethod(): Unit = {}
}

class ControllerHttpResponseToClosureAdapter(
    ctx: RequestContext,
    endpoint: ServerEndpoint
) extends Closure[HttpResponse] {
  override def call(): HttpResponse = {
    def resp = HttpResponse(endpoint.getStatus, endpoint.getBody)
    ctx.complete(resp)
    resp
  }
}
class ControllerHttpRedirectResponseToClosureAdapter(
    ctx: RequestContext
) extends Closure[Unit] {
  override def call() = {
    ctx.redirect(
      REDIRECT.getBody,
      spray.http.StatusCodes.Redirection(REDIRECT.getStatus)("moved", "", "")
    )
  }
}
class BlockClosureAdapter(block: () => HttpResponse)
    extends Closure[HttpResponse] {
  override def call(): HttpResponse = block()
}

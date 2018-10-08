import akka.actor.{ActorSystem, Props}
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import datadog.trace.agent.test.TestUtils
import datadog.trace.api.Trace
import spray.can.Http
import spray.http.{HttpResponse, StatusCodes}
import spray.routing.HttpServiceActor

import scala.concurrent.Await
import scala.concurrent.duration._

object SprayHttpTestWebServer {

  val port = TestUtils.randomOpenPort()

  implicit var system: ActorSystem = null

  implicit val timeout = Timeout(5.seconds)

  def start(): Unit = synchronized {
    if (null == system) {
      import scala.concurrent.duration._
      system = ActorSystem("on-spray-can")
      val service = system.actorOf(Props[ServiceActor], "test-service")
      Await.result(IO(Http) ? Http.Bind(service, interface = "localhost", port = port), 10 seconds)
    }
  }

  def stop(): Unit = synchronized {
    system.shutdown()
  }
}

class ServiceActor extends HttpServiceActor {
  def receive = runRoute {
    path("test") {
      get { ctx =>
        tracedMethod()
        ctx.complete("Hello unit test.")
      }
    } ~
      path("throw-handler") {
        get { ctx =>
          sys.error("Oh no handler")
        }
      } ~
      path("server-error") {
        get { ctx =>
          ctx.complete(HttpResponse(StatusCodes.InternalServerError, "Bad things happen"))
        }
      } ~
      path("timeout") {
        get { ctx =>
          Thread.sleep(10000)
          ctx.complete("Hello very slow unit test.")
        }
      }
  }

  @Trace
  def tracedMethod(): Unit = {
  }
}

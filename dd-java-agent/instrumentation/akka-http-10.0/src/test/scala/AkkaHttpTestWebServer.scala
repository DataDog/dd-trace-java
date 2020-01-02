import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.ExceptionHandler
import akka.stream.ActorMaterializer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint._
import groovy.lang.Closure

import scala.concurrent.Await

// FIXME: This doesn't work because we don't support bindAndHandle.
object AkkaHttpTestWebServer {
  implicit val system = ActorSystem("my-system")
  implicit val materializer = ActorMaterializer()
  // needed for the future flatMap/onComplete in the end
  implicit val executionContext = system.dispatcher

  val exceptionHandler = ExceptionHandler {
    case ex: Exception => complete(HttpResponse(status = EXCEPTION.getStatus).withEntity(ex.getMessage))
  }

  val route = {
    extractRequest { req =>
      val endpoint = HttpServerTest.ServerEndpoint.forPath(req.uri.path.toString())
      handleExceptions(exceptionHandler) {
        complete(HttpServerTest.controller(endpoint, new Closure[HttpResponse](()) {
          def doCall(): HttpResponse = {
            val resp = HttpResponse(status = endpoint.getStatus) //.withHeaders(headers.Type)resp.contentType = "text/plain"
            endpoint match {
              case SUCCESS => resp.withEntity(endpoint.getBody)
              case QUERY_PARAM => resp.withEntity(req.uri.queryString().orNull)
              case REDIRECT => resp.withHeaders(headers.Location(endpoint.getBody))
              case ERROR => resp.withEntity(endpoint.getBody)
              case EXCEPTION => throw new Exception(endpoint.getBody)
              case _ => HttpResponse(status = NOT_FOUND.getStatus).withEntity(NOT_FOUND.getBody)
            }
          }
        }))
      }
    }
  }

  private var binding: ServerBinding = null

  def start(port: Int): Unit = synchronized {
    if (null == binding) {
      import scala.concurrent.duration._
      binding = Await.result(Http().bindAndHandle(route, "localhost", port), 10 seconds)
    }
  }

  def stop(): Unit = synchronized {
    if (null != binding) {
      binding.unbind()
      system.terminate()
      binding = null
    }
  }
}

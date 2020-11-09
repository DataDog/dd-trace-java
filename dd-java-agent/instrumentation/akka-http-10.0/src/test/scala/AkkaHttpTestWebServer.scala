import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{
  Directive,
  Directive0,
  ExceptionHandler,
  RouteResult
}
import akka.http.scaladsl.util.FastFuture.EnhancedFuture
import akka.stream.ActorMaterializer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint._
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import groovy.lang.Closure
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.control.NonFatal

object AkkaHttpTestWebServer {
  implicit val system = ActorSystem("my-system")
  implicit val materializer = ActorMaterializer()
  // needed for the future flatMap/onComplete in the end
  implicit val executionContext = system.dispatcher

  val exceptionHandler = ExceptionHandler {
    case e: Exception =>
      val span = activeSpan()
      TraceUtils.handleException(span, e)
      complete(
        HttpResponse(status = EXCEPTION.getStatus, entity = e.getMessage)
      )
  }

  // Since the akka-http route DSL produces a Route that is evaluated for every
  // incoming request, we need to wrap the HttpServerTest.controller call and exception
  // handling in a custom Directive
  def withController: Directive0 = Directive[Unit] { inner => ctx =>
    def handleException: PartialFunction[Throwable, Future[RouteResult]] =
      exceptionHandler andThen (_(ctx.withAcceptAll))
    val uri = ctx.request.uri
    val endpoint = HttpServerTest.ServerEndpoint.forPath(uri.path.toString())
    HttpServerTest.controller(
      endpoint,
      new Closure[Future[RouteResult]](()) {
        def doCall(): Future[RouteResult] = {
          try inner(())(ctx).fast
            .recoverWith(handleException)(ctx.executionContext)
          catch {
            case NonFatal(e) ⇒
              handleException
                .applyOrElse[Throwable, Future[RouteResult]](e, throw _)
          }
        }
      }
    )
  }

  val route = withController {
    get {
      path(SUCCESS.rawPath) {
        complete(
          HttpResponse(status = SUCCESS.getStatus, entity = SUCCESS.getBody)
        )
      } ~ path(QUERY_PARAM.rawPath) {
        parameter("some") { query =>
          complete(
            HttpResponse(
              status = QUERY_PARAM.getStatus,
              entity = s"some=$query"
            )
          )
        }
      } ~ path(REDIRECT.rawPath) {
        redirect(Uri(REDIRECT.getBody), StatusCodes.Found)
      } ~ path(ERROR.rawPath) {
        complete(HttpResponse(status = ERROR.getStatus, entity = ERROR.getBody))
      } ~ path(EXCEPTION.rawPath) {
        throw new Exception(EXCEPTION.getBody)
      }
    }
  }

  private var binding: ServerBinding = null

  def start(port: Int): Unit = synchronized {
    if (null == binding) {
      import scala.concurrent.duration._
      binding =
        Await.result(Http().bindAndHandle(route, "localhost", port), 10 seconds)
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

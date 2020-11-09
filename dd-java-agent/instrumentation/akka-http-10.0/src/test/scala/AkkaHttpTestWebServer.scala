import AkkaHttpTestWebServer.Binder
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{
  Directive,
  Directive0,
  ExceptionHandler,
  Route,
  RouteResult
}
import akka.http.scaladsl.util.FastFuture.EnhancedFuture
import akka.stream.{ActorMaterializer, Materializer}
import com.typesafe.config.Config
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint._
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import groovy.lang.Closure
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.control.NonFatal

class AkkaHttpTestWebServer(port: Int, binder: Binder) {
  implicit val system = {
    val name = s"${binder.name}-$port"
    binder.config match {
      case None         => ActorSystem(name)
      case Some(config) => ActorSystem(name, config)
    }
  }
  implicit val materializer = ActorMaterializer()
  private val bindingFuture: Future[ServerBinding] =
    Await.ready(binder.bind(port), 10 seconds)

  def stop(): Unit = {
    import materializer.executionContext
    bindingFuture
      .flatMap(_.unbind())
      .onComplete(_ => system.terminate())
  }
}

object AkkaHttpTestWebServer {

  trait Binder {
    def name: String
    def config: Option[Config] = None
    def bind(port: Int)(
        implicit system: ActorSystem,
        materializer: Materializer
    ): Future[ServerBinding]
  }

  val BindAndHandle: Binder = new Binder {
    override def name: String = "bind-and-handle"
    override def bind(port: Int)(
        implicit system: ActorSystem,
        materializer: Materializer
    ): Future[ServerBinding] = {
      import materializer.executionContext
      Http().bindAndHandle(route, "localhost", port)
    }
  }

  val BindAndHandleAsyncWithRouteAsyncHandler: Binder = new Binder {
    override def name: String = "bind-and-handle-async-with-route-async-handler"
    override def bind(port: Int)(
        implicit system: ActorSystem,
        materializer: Materializer
    ): Future[ServerBinding] = {
      import materializer.executionContext
      Http().bindAndHandleAsync(Route.asyncHandler(route), "localhost", port)
    }
  }

  val BindAndHandleSync: Binder = new Binder {
    override def name: String = "bind-and-handle-sync"
    override def bind(port: Int)(
        implicit system: ActorSystem,
        materializer: Materializer
    ): Future[ServerBinding] = {
      Http().bindAndHandleSync(syncHandler, "localhost", port)
    }
  }

  val BindAndHandleAsync: Binder = new Binder {
    override def name: String = "bind-and-handle-async"
    override def bind(port: Int)(
        implicit system: ActorSystem,
        materializer: Materializer
    ): Future[ServerBinding] = {
      import materializer.executionContext
      Http().bindAndHandleAsync(asyncHandler, "localhost", port)
    }
  }

  // This part defines the routes using the Scala routing DSL
  // ---------------------------------------------------------------------- //
  private val exceptionHandler = ExceptionHandler {
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
  private def withController: Directive0 = Directive[Unit] { inner => ctx =>
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

  private def route(implicit ec: ExecutionContext): Route = withController {
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
      } ~ pathPrefix("injected-id") {
        path("ping" / IntNumber) { id =>
          val traceId = AgentTracer.activeSpan().getTraceId
          complete(s"pong $id -> $traceId")
        } ~ path("fing" / IntNumber) { id =>
          // force the response to happen on another thread or in another context
          onSuccess(Future {
            Thread.sleep(10); id
          }) { fid =>
            val traceId = AgentTracer.activeSpan().getTraceId
            complete(s"fong $fid -> $traceId")
          }
        }
      }
    }
  }

  // This part defines the sync and async handler functions
  // ---------------------------------------------------------------------- //

  private val syncHandler: HttpRequest => HttpResponse = {
    case HttpRequest(GET, uri: Uri, _, _, _) => {
      val path = uri.path.toString()
      val endpoint = HttpServerTest.ServerEndpoint.forPath(path)
      HttpServerTest.controller(
        endpoint,
        new Closure[HttpResponse](()) {
          def doCall(): HttpResponse = {
            val resp = HttpResponse(status = endpoint.getStatus)
            endpoint match {
              case SUCCESS     => resp.withEntity(endpoint.getBody)
              case QUERY_PARAM => resp.withEntity(uri.queryString().orNull)
              case REDIRECT =>
                resp.withHeaders(headers.Location(endpoint.getBody))
              case ERROR     => resp.withEntity(endpoint.getBody)
              case EXCEPTION => throw new Exception(endpoint.getBody)
              case _ =>
                if (path.startsWith("/injected-id/")) {
                  val groups = path.split('/')
                  if (groups.size == 4) { // The path starts with a / and has 3 segments
                    val traceId = AgentTracer.activeSpan().getTraceId
                    val id = groups(3).toInt
                    groups(2) match {
                      case "ping" =>
                        return HttpResponse(entity = s"pong $id -> $traceId")
                      case "fing" =>
                        return HttpResponse(entity = s"fong $id -> $traceId")
                      case _ =>
                    }
                  }
                }
                HttpResponse(status = NOT_FOUND.getStatus)
                  .withEntity(NOT_FOUND.getBody)
            }
          }
        }
      )
    }
  }

  private def asyncHandler(
      implicit ec: ExecutionContext
  ): HttpRequest => Future[HttpResponse] = { request =>
    Future { syncHandler(request) }
  }
}

import PekkoHttpTestWebServer.Binder
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.http.scaladsl.model.HttpMethods.GET
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server._
import org.apache.pekko.http.scaladsl.settings.ServerSettings
import org.apache.pekko.http.scaladsl.util.FastFuture.EnhancedFuture
import org.apache.pekko.stream.{ActorMaterializer, Materializer}
import com.typesafe.config.Config
import datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint._
import datadog.trace.agent.test.base.{HttpServer, HttpServerTest}
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import groovy.lang.Closure

import java.net.URI
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.control.NonFatal

class PekkoHttpTestWebServer(binder: Binder) extends HttpServer {
  implicit val system = {
    val name = s"${binder.name}"
    binder.config match {
      case None         => ActorSystem(name)
      case Some(config) => ActorSystem(name, config)
    }
  }
  implicit val materializer                      = ActorMaterializer()
  private var port: Int                          = 0
  private var portBinding: Future[ServerBinding] = null

  override def start(): Unit = {
    portBinding = Await.ready(binder.bind(0), 10 seconds)
    port = portBinding.value.get.get.localAddress.getPort
  }

  override def stop(): Unit = {
    import materializer.executionContext
    portBinding
      .flatMap(_.unbind())
      .onComplete(_ => system.terminate())
  }

  override def address(): URI = {
    new URI("http://localhost:" + port + "/")
  }
}

object PekkoHttpTestWebServer {

  trait Binder {
    def name: String

    def config: Option[Config] = None

    def bind(port: Int)(implicit
        system: ActorSystem,
        materializer: Materializer
    ): Future[ServerBinding]
  }

  val BindAndHandle: Binder = new Binder {
    override def name: String = "bind-and-handle"

    override def bind(port: Int)(implicit
        system: ActorSystem,
        materializer: Materializer
    ): Future[ServerBinding] = {
      import materializer.executionContext
      Http().bindAndHandle(route, "localhost", port)
    }
  }

  val BindAndHandleAsyncWithRouteAsyncHandler: Binder = new Binder {
    override def name: String = "bind-and-handle-async-with-route-async-handler"

    override def bind(port: Int)(implicit
        system: ActorSystem,
        materializer: Materializer
    ): Future[ServerBinding] = {
      import materializer.executionContext
      Http().bindAndHandleAsync(Route.asyncHandler(route), "localhost", port)
    }
  }

  val BindAndHandleSync: Binder = new Binder {
    override def name: String = "bind-and-handle-sync"

    override def bind(port: Int)(implicit
        system: ActorSystem,
        materializer: Materializer
    ): Future[ServerBinding] = {
      Http().bindAndHandleSync(syncHandler, "localhost", port)
    }
  }

  val BindAndHandleAsync: Binder = new Binder {
    override def name: String = "bind-and-handle-async"

    override def bind(port: Int)(implicit
        system: ActorSystem,
        materializer: Materializer
    ): Future[ServerBinding] = {
      import materializer.executionContext
      Http().bindAndHandleAsync(asyncHandler, "localhost", port)
    }
  }

  val BindAndHandleAsyncHttp2: Binder = new Binder {
    override def name: String = "bind-and-handle-async-http2"

    override def bind(port: Int)(implicit
        system: ActorSystem,
        materializer: Materializer
    ): Future[ServerBinding] = {
      import materializer.executionContext
      val serverSettings = enableHttp2(ServerSettings(system))
      Http().bindAndHandleAsync(
        asyncHandler,
        "localhost",
        port,
        settings = serverSettings
      )
    }
  }

  // This part defines the routes using the Scala routing DSL
  // ---------------------------------------------------------------------- //
  private val exceptionHandler = ExceptionHandler { case e: Exception =>
    val span = activeSpan()
    if (span != null) {
      // The exception handler is bypassing the normal instrumentation flow, so we need to handle things here
      TraceUtils.handleException(span, e)
      span.finish()
    }
    complete(
      HttpResponse(status = EXCEPTION.getStatus, entity = e.getMessage)
    )
  }

  // Since the pekko-http route DSL produces a Route that is evaluated for every
  // incoming request, we need to wrap the HttpServerTest.controller call and exception
  // handling in a custom Directive
  private def withController: Directive0 = Directive[Unit] { inner => ctx =>
    def handleException: PartialFunction[Throwable, Future[RouteResult]] =
      exceptionHandler andThen (_(ctx.withAcceptAll))

    val uri      = ctx.request.uri
    val endpoint = HttpServerTest.ServerEndpoint.forPath(uri.path.toString())
    HttpServerTest.controller(
      endpoint,
      new Closure[Future[RouteResult]](()) {
        def doCall(): Future[RouteResult] = {
          try inner(())(ctx).fast
            .recoverWith(handleException)(ctx.executionContext)
          catch {
            case NonFatal(e) =>
              handleException
                .applyOrElse[Throwable, Future[RouteResult]](e, throw _)
          }
        }
      }
    )
  }

  private val defaultHeader =
    RawHeader(HttpServerTest.getIG_RESPONSE_HEADER, HttpServerTest.getIG_RESPONSE_HEADER_VALUE)

  def route(implicit ec: ExecutionContext): Route = withController {
    respondWithDefaultHeader(defaultHeader) {
      get {
        path(SUCCESS.relativePath) {
          complete(
            HttpResponse(status = SUCCESS.getStatus, entity = SUCCESS.getBody)
          )
        } ~ path(FORWARDED.relativePath) {
          headerValueByName("x-forwarded-for") { address =>
            complete(
              HttpResponse(status = FORWARDED.getStatus, entity = address)
            )
          }
        } ~ path(
          QUERY_PARAM.relativePath | QUERY_ENCODED_BOTH.relativePath | QUERY_ENCODED_QUERY.relativePath
        ) {
          parameter("some") { query =>
            complete(
              HttpResponse(
                status = QUERY_PARAM.getStatus,
                entity = s"some=$query"
              )
            )
          }
        } ~ path(REDIRECT.relativePath) {
          redirect(Uri(REDIRECT.getBody), StatusCodes.Found)
        } ~ path(ERROR.relativePath) {
          complete(HttpResponse(status = ERROR.getStatus, entity = ERROR.getBody))
        } ~ path(EXCEPTION.relativePath) {
          throw new Exception(EXCEPTION.getBody)
        } ~ pathPrefix("injected-id") {
          path("ping" / IntNumber) { id =>
            val traceId = AgentTracer.activeSpan().getTraceId
            complete(s"pong $id -> $traceId")
          } ~ path("fing" / IntNumber) { id =>
            // force the response to happen on another thread or in another context
            onSuccess(Future {
              Thread.sleep(10);
              id
            }) { fid =>
              val traceId = AgentTracer.activeSpan().getTraceId
              complete(s"fong $fid -> $traceId")
            }
          }
        }
      }
    }
  }

  // This part defines the sync and async handler functions
  // ---------------------------------------------------------------------- //

  val syncHandler: HttpRequest => HttpResponse = {
    case HttpRequest(GET, uri: Uri, _, _, _) => {
      val path     = uri.path.toString()
      val endpoint = HttpServerTest.ServerEndpoint.forPath(path)
      HttpServerTest
        .controller(
          endpoint,
          new Closure[HttpResponse](()) {
            def doCall(): HttpResponse = {
              val resp = HttpResponse(status = endpoint.getStatus)
              endpoint match {
                case SUCCESS   => resp.withEntity(endpoint.getBody)
                case FORWARDED => resp.withEntity(endpoint.getBody) // cheating
                case QUERY_PARAM | QUERY_ENCODED_BOTH | QUERY_ENCODED_QUERY =>
                  resp.withEntity(uri.queryString().orNull)
                case REDIRECT =>
                  resp.withHeaders(headers.Location(endpoint.getBody))
                case ERROR     => resp.withEntity(endpoint.getBody)
                case EXCEPTION => throw new Exception(endpoint.getBody)
                case _ =>
                  if (path.startsWith("/injected-id/")) {
                    val groups = path.split('/')
                    if (groups.size == 4) { // The path starts with a / and has 3 segments
                      val traceId = AgentTracer.activeSpan().getTraceId
                      val id      = groups(3).toInt
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
        .withDefaultHeaders(defaultHeader)
    }
  }

  def asyncHandler(implicit
      ec: ExecutionContext
  ): HttpRequest => Future[HttpResponse] = { request =>
    Future {
      syncHandler(request)
    }
  }

  def enableHttp2(serverSettings: ServerSettings): ServerSettings = {
    val previewServerSettings =
      serverSettings.previewServerSettings.withEnableHttp2(true)
    serverSettings.withPreviewServerSettings(previewServerSettings)
  }
}

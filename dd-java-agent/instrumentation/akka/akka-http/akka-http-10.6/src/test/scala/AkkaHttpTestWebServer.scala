import AkkaHttpTestWebServer.Binder
import akka.actor.ActorSystem
import akka.http.javadsl.marshallers.jackson.Jackson
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.HttpEntity.apply
import akka.http.scaladsl.model.HttpMethods.{GET, POST}
import akka.http.scaladsl.model.Multipart.FormData
import akka.http.scaladsl.model.Multipart.FormData.BodyPart
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.settings.ServerSettings
import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers.defaultUrlEncodedFormDataUnmarshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller.messageUnmarshallerFromEntityUnmarshaller
import akka.http.scaladsl.unmarshalling.{
  FromEntityUnmarshaller,
  MultipartUnmarshallers,
  Unmarshal,
  Unmarshaller
}
import akka.http.scaladsl.util.FastFuture.EnhancedFuture
import akka.http.scaladsl.{Http, model}
import akka.stream.{ActorMaterializer, Materializer}
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import datadog.appsec.api.blocking.{Blocking, BlockingException}
import datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint._
import datadog.trace.agent.test.base.{HttpServer, HttpServerTest}
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import groovy.lang.Closure
import spray.json.{JsObject, JsString, JsValue, RootJsonFormat, deserializationError}

import java.net.URI
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.control.NonFatal

class AkkaHttpTestWebServer(binder: Binder) extends HttpServer {
  implicit val system: ActorSystem = {
    val name = s"${binder.name}"
    binder.config match {
      case None         => ActorSystem(name)
      case Some(config) => ActorSystem(name, config)
    }
  }
  implicit val materializer: ActorMaterializer   = ActorMaterializer()
  private var port: Int                          = 0
  private var portBinding: Future[ServerBinding] = _

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

object AkkaHttpTestWebServer {

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

    override def config: Option[Config] = Some(
      ConfigFactory
        .load()
        .withValue("akka.http.server.request-timeout", ConfigValueFactory.fromAnyRef("300 s"))
        .withValue("akka.http.server.idle-timeout", ConfigValueFactory.fromAnyRef("300 s"))
    )

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
  private val exceptionHandler = ExceptionHandler {
    case e: Exception if !e.isInstanceOf[BlockingException] =>
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

    val uri      = ctx.request.uri
    val endpoint = HttpServerTest.ServerEndpoint.forPath(uri.path.toString())
    HttpServerTest.controller(
      endpoint,
      new Closure[Future[RouteResult]](()) {
        def doCall(): Future[RouteResult] = {
          try
            inner(())(ctx).fast
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

  // force a rejection due to BlockingException to throw so that the error
  // can be recorded in the span
  private val blockingRejectionHandler: RejectionHandler = RejectionHandler
    .newBuilder()
    .handle({ case MalformedRequestContentRejection(_, cause: BlockingException) =>
      throw cause
    })
    .result()

  def route(implicit ec: ExecutionContext): Route = withController {
    handleRejections(blockingRejectionHandler) {
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
          } ~ path(USER_BLOCK.relativePath) {
            Blocking.forUser("user-to-block").blockIfMatch()
            complete(
              HttpResponse(status = SUCCESS.getStatus, entity = "Should not be reached")
            )
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
                Thread.sleep(10)
                id
              }) { fid =>
                val traceId = AgentTracer.activeSpan().getTraceId
                complete(s"fong $fid -> $traceId")
              }
            }
          } ~ path(USER_BLOCK.relativePath()) {
            Blocking.forUser("user-to-block").blockIfMatch()
            complete(HttpResponse(status = 200, entity = "should never be reached"))
          }
        } ~ post {
          path(CREATED.relativePath()) {
            entity(as[String]) { s =>
              complete(
                HttpResponse(
                  status = CREATED.getStatus,
                  entity = s"created: $s"
                )
              )
            }
          } ~
            path(BODY_URLENCODED.relativePath()) {
              formFieldMultiMap { m =>
                complete(
                  HttpResponse(
                    status = BODY_URLENCODED.getStatus,
                    entity = m.toStringAsGroovy
                  )
                )
              }
            } ~
            path(BODY_JSON.relativePath()) {
              parameter(Symbol("variant") ?) {
                case Some("spray") =>
                  entity(
                    Unmarshaller.messageUnmarshallerFromEntityUnmarshaller(sprayMapUnmarshaller)
                  ) { m =>
                    complete(
                      HttpResponse(
                        status = BODY_JSON.getStatus,
                        entity = SprayMapFormat.write(m).compactPrint
                      )
                    )
                  }
                case _ => // jackson
                  entity(
                    Unmarshaller.messageUnmarshallerFromEntityUnmarshaller(jacksonMapUnmarshaller)
                  ) { m =>
                    complete(
                      HttpResponse(
                        status = BODY_JSON.getStatus,
                        entity = SprayMapFormat.write(m).compactPrint
                      )
                    )
                  }
              }
            } ~
            path(BODY_MULTIPART.relativePath()) {
              parameter(Symbol("variant") ?) {
                case Some("strictUnmarshaller") =>
                  entity(as[Multipart.FormData.Strict]) { formData =>
                    val m = formData.strictParts
                      .groupBy(_.name)
                      .mapValues(
                        _.map((bp: BodyPart.Strict) => bp.entity.data.utf8String).toList
                      )
                    complete(
                      HttpResponse(
                        status = BODY_MULTIPART.getStatus,
                        entity = m.toStringAsGroovy
                      )
                    )
                  }
                case _ =>
                  formFieldMultiMap { m =>
                    complete(
                      HttpResponse(
                        status = BODY_MULTIPART.getStatus,
                        entity = m.toStringAsGroovy
                      )
                    )
                  }
              }
            }
        }
      }
    }
  }

  // This part defines the sync and async handler functions
  // ---------------------------------------------------------------------- //

  val syncHandler: HttpRequest => HttpResponse = { case HttpRequest(GET, uri: Uri, _, _, _) =>
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
              case ERROR      => resp.withEntity(endpoint.getBody)
              case EXCEPTION  => throw new Exception(endpoint.getBody)
              case USER_BLOCK => {
                Blocking.forUser("user-to-block").blockIfMatch()
                // should never be output:
                resp.withEntity("should never be reached")
              }
              case _ =>
                if (path.startsWith("/injected-id/")) {
                  val groups = path.split('/')
                  if (groups.length == 4) { // The path starts with a / and has 3 segments
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

  def asyncHandler(implicit
      ec: ExecutionContext,
      mat: Materializer
  ): HttpRequest => Future[HttpResponse] = {
    case request @ HttpRequest(POST, uri, _, entity, _) =>
      val path     = request.uri.path.toString
      val endpoint = HttpServerTest.ServerEndpoint.forPath(path)

      endpoint match {
        case CREATED =>
          Unmarshal(entity).to[String].map { bodyStr =>
            HttpResponse(status = CREATED.getStatus)
              .withEntity(s"${CREATED.getBody}: $bodyStr")
          }
        case BODY_MULTIPART =>
          uri.query().get("variant") match {
            case Some("strictUnmarshaller") =>
              val eventualStrict = Unmarshal(entity).to[FormData.Strict]
              eventualStrict.map { s =>
                HttpResponse(status = BODY_MULTIPART.getStatus)
                  .withEntity(s.toStringAsGroovy)
              }
            case _ =>
              val fd             = Unmarshal(entity).to[Multipart.FormData]
              val eventualStrict = fd.flatMap(_.toStrict(500 millis))
              eventualStrict.map { s =>
                HttpResponse(status = BODY_MULTIPART.getStatus)
                  .withEntity(s.toStringAsGroovy)
              }
          }
        case BODY_URLENCODED =>
          val eventualData = Unmarshal(entity).to[model.FormData]
          eventualData.map { d =>
            HttpResponse(status = BODY_URLENCODED.getStatus)
              .withEntity(d.toStringAsGroovy)
          }
        case BODY_JSON =>
          val unmarshaller = uri.query().get("variant") match {
            case Some("spray") => sprayMapUnmarshaller
            case _             => jacksonMapUnmarshaller
          }
          val eventualData = Unmarshal(entity).to[Map[String, String]](unmarshaller, ec, mat)
          eventualData.map { d =>
            HttpResponse(status = BODY_URLENCODED.getStatus)
              .withEntity(SprayMapFormat.write(d).compactPrint)
          }
        case _ => Future.successful(HttpResponse(404))
      }
    case request =>
      Future {
        syncHandler(request)
      }
  }

  def enableHttp2(serverSettings: ServerSettings): ServerSettings = {
    val previewServerSettings =
      serverSettings.previewServerSettings.withEnableHttp2(true)
    serverSettings.withPreviewServerSettings(previewServerSettings)
  }

  implicit class MapExtensions[A](m: Iterable[(String, A)]) {
    def toStringAsGroovy: String = {
      def valueToString(value: Object): String = value match {
        case seq: Seq[_] =>
          seq.map(x => valueToString(x.asInstanceOf[Object])).mkString("[", ",", "]")
        case other => other.toString
      }

      m.map { case (key, value) => s"$key:${valueToString(value.asInstanceOf[Object])}" }
        .mkString("[", ",", "]")
    }
  }

  implicit class MultipartFormDataStrictExtensions(strict: Multipart.FormData.Strict) {
    def toStringAsGroovy: String =
      strict.strictParts
        .groupBy(_.name)
        .mapValues(
          _.map((bp: BodyPart.Strict) => bp.entity.data.utf8String).toList
        )
        .toStringAsGroovy
  }

  implicit class FormDataExtensions(formData: model.FormData) {
    def toStringAsGroovy: String = formData.fields.toMultiMap.toStringAsGroovy
  }

  implicit def strictMultipartFormDataUnmarshaller
      : FromEntityUnmarshaller[Multipart.FormData.Strict] = {
    val toStrictUnmarshaller = Unmarshaller.withMaterializer[HttpEntity, HttpEntity.Strict] {
      implicit ec => implicit mat => entity =>
        entity.toStrict(1000.millis)
    }
    val toFormDataUnmarshaller = MultipartUnmarshallers.multipartFormDataUnmarshaller
    val downcastUnmarshaller = Unmarshaller.strict[Multipart.FormData, Multipart.FormData.Strict] {
      case strict: Multipart.FormData.Strict => strict
      case _ => throw new RuntimeException("Expected Strict form data at this point")
    }

    toStrictUnmarshaller.andThen(toFormDataUnmarshaller).andThen(downcastUnmarshaller)
  }

  val jacksonMapUnmarshaller: FromEntityUnmarshaller[Map[String, String]] = {
    Jackson
      .unmarshaller(classOf[java.util.Map[String, String]])
      .asScala
      .map(javaMap => {
        import scala.collection.JavaConverters._
        javaMap.asScala.toMap
      })
  }

  object SprayMapFormat extends RootJsonFormat[Map[String, String]] {
    def write(map: Map[String, String]): JsObject = JsObject(map.mapValues(JsString(_)).toMap)

    def read(value: JsValue): Map[String, String] = value match {
      case JsObject(fields) =>
        fields.collect { case (k, JsString(v)) =>
          k -> v
        }
      case _ => deserializationError("Expected a JSON object")
    }
  }

  val sprayMapUnmarshaller: FromEntityUnmarshaller[Map[String, String]] =
    SprayJsonSupport.sprayJsonUnmarshaller[Map[String, String]](SprayMapFormat)
}

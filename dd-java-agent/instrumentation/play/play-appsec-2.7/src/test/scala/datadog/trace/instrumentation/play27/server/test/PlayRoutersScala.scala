package datadog.trace.instrumentation.play27.server.test

import datadog.appsec.api.blocking.Blocking
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.base.HttpServerTest.{
  ServerEndpoint,
  getIG_RESPONSE_HEADER,
  getIG_RESPONSE_HEADER_VALUE
}
import datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint._
import datadog.trace.instrumentation.play26.server.TestHttpErrorHandler.CustomRuntimeException
import datadog.trace.instrumentation.play27.server.test.ImplicitConversions.MapExtensions
import groovy.lang.Closure
import play.api.BuiltInComponents
import play.api.libs.json._
import play.api.mvc._
import play.api.routing.Router
import play.api.routing.sird._

import java.util.concurrent.ExecutorService
import scala.concurrent.{ExecutionContext, Future}
import scala.xml.NodeSeq

object PlayRoutersScala {

  def async(executor: ExecutorService)(components: BuiltInComponents): Router = {
    val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)
    val parser               = components.defaultBodyParser

    import components._

    def controller(endpoint: ServerEndpoint)(block: => Result): Future[Result] = {
      Future {
        HttpServerTest.controller(
          endpoint,
          new Closure[Result](this) {
            def doCall(): Result =
              block.withHeaders((getIG_RESPONSE_HEADER, getIG_RESPONSE_HEADER_VALUE))
          }
        )
      }(ec)
    }

    Router.from {
      case GET(p"/success") =>
        defaultActionBuilder.async {
          controller(SUCCESS) {
            Results.Ok(SUCCESS.getBody)
          }
        }

      case GET(p"/redirect") =>
        defaultActionBuilder.async {
          controller(REDIRECT) {
            Results.Redirect(REDIRECT.getBody, 302)
          }
        }

      case GET(p"/forwarded") =>
        defaultActionBuilder.async { request =>
          controller(FORWARDED) {
            Results.Status(FORWARDED.getStatus)(
              request.headers.get("X-Forwarded-For").getOrElse("(no header)")
            )
          }
        }

      // endpoint is not special; error results are returned the same way
      case GET(p"/error-status") =>
        defaultActionBuilder.async {
          controller(ERROR) {
            Results.Status(ERROR.getStatus)(ERROR.getBody)
          }
        }

      case GET(p"/exception") =>
        defaultActionBuilder.async {
          controller(EXCEPTION) {
            throw new RuntimeException(EXCEPTION.getBody)
          }
        }

      case GET(p"/custom-exception") =>
        defaultActionBuilder.async {
          controller(CUSTOM_EXCEPTION) {
            throw new CustomRuntimeException(CUSTOM_EXCEPTION.getBody)
          }
        }

      case GET(p"/not-here") =>
        defaultActionBuilder.async {
          controller(NOT_HERE) {
            Results.NotFound
          }
        }

      case GET(p"/user-block") =>
        defaultActionBuilder.async {
          controller(USER_BLOCK) {
            Blocking.forUser("user-to-block").blockIfMatch()
            Results.Ok("should never be reached")
          }
        }

      case GET(p"/query" ? q"some=$some") =>
        defaultActionBuilder.async {
          controller(QUERY_PARAM) {
            Results.Status(QUERY_PARAM.getStatus)(s"some=$some")
          }
        }

      case GET(p"/encoded_query" ? q"some=$some") =>
        defaultActionBuilder.async {
          controller(QUERY_ENCODED_QUERY) {
            Results.Status(QUERY_ENCODED_QUERY.getStatus)(s"some=$some")
          }
        }

      case GET(p"/encoded%20path%20query" ? q"some=$some") =>
        defaultActionBuilder.async {
          controller(QUERY_ENCODED_BOTH) {
            Results.Status(QUERY_ENCODED_BOTH.getStatus)(s"some=$some")
          }
        }

      case GET(p"/path/$path/param") =>
        defaultActionBuilder.async {
          controller(PATH_PARAM) {
            Results.Status(PATH_PARAM.getStatus)(path)
          }
        }

      case POST(p"/created") =>
        defaultActionBuilder.async(parser) { request =>
          controller(CREATED) {
            val body: String = request.body.asText.getOrElse("")
            Results.Created(s"created: $body")
          }
        }

      case POST(p"/body-urlencoded") =>
        defaultActionBuilder.async(parser) { request =>
          controller(BODY_URLENCODED) {
            val body: Map[String, Seq[String]] = request.body.asFormUrlEncoded.getOrElse(Map.empty)
            Results.Ok(body.toStringAsGroovy)
          }
        }

      case POST(p"/body-multipart") =>
        defaultActionBuilder.async(parser) { request =>
          controller(BODY_MULTIPART) {
            val body: Map[String, scala.Seq[String]] = request.body.asMultipartFormData
              .getOrElse(
                MultipartFormData(Map.empty, scala.Seq.empty, scala.Seq.empty)
              )
              .asFormUrlEncoded
            Results.Ok(body.toStringAsGroovy)
          }
        }

      case POST(p"/body-json") =>
        defaultActionBuilder.async(parser) { request =>
          controller(BODY_JSON) {
            val body: JsValue = request.body.asJson.getOrElse(JsNull)
            Results.Ok(body)
          }
        }

      case POST(p"/body-xml") =>
        defaultActionBuilder.async(parser) { request =>
          controller(BODY_XML) {
            val body: NodeSeq = request.body.asXml.getOrElse(NodeSeq.Empty)
            Results.Ok(body.toString())
          }
        }
    }
  }

}

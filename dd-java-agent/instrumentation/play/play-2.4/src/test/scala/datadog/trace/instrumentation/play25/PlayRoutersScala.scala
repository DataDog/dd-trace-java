package datadog.trace.instrumentation.play25

import datadog.appsec.api.blocking.Blocking
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint._
import datadog.trace.agent.test.base.HttpServerTest.{
  ServerEndpoint,
  getIG_RESPONSE_HEADER,
  getIG_RESPONSE_HEADER_VALUE
}
import datadog.trace.instrumentation.play25.Util.MapExtensions
import groovy.lang.Closure
import play.api.BuiltInComponents
import play.api.libs.json._
import play.api.mvc._
import play.api.routing.Router
import play.api.routing.sird._

import java.util.concurrent.{Executor, ExecutorService}
import scala.concurrent.{ExecutionContext, Future}

object PlayRoutersScala {

  def async(executor: Executor): Router = {
    val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)
    val parser               = BodyParsers.parse.default

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
        Action.async {
          controller(SUCCESS) {
            Results.Ok(SUCCESS.getBody)
          }
        }

      case GET(p"/redirect") =>
        Action.async {
          controller(REDIRECT) {
            Results.Redirect(REDIRECT.getBody, 302)
          }
        }

      case GET(p"/forwarded") =>
        Action.async { request =>
          controller(FORWARDED) {
            Results.Status(FORWARDED.getStatus)(
              request.headers.get("X-Forwarded-For").getOrElse("(no header)")
            )
          }
        }

      // endpoint is not special; error results are returned the same way
      case GET(p"/error-status") =>
        Action.async {
          controller(ERROR) {
            Results.Status(ERROR.getStatus)(ERROR.getBody)
          }
        }

      case GET(p"/exception") =>
        Action.async {
          controller(EXCEPTION) {
            throw new RuntimeException(EXCEPTION.getBody)
          }
        }

      case GET(p"/custom-exception") =>
        Action.async {
          controller(CUSTOM_EXCEPTION) {
            throw Util.createCustomException(CUSTOM_EXCEPTION.getBody)
          }
        }

      case GET(p"/not-here") =>
        Action.async {
          controller(NOT_HERE) {
            Results.NotFound
          }
        }

      case GET(p"/user-block") =>
        Action.async {
          controller(USER_BLOCK) {
            Blocking.forUser("user-to-block").blockIfMatch()
            Results.Ok("should never be reached")
          }
        }

      case GET(p"/query" ? q"some=$some") =>
        Action.async {
          controller(QUERY_PARAM) {
            Results.Status(QUERY_PARAM.getStatus)(s"some=$some")
          }
        }

      case GET(p"/encoded_query" ? q"some=$some") =>
        Action.async {
          controller(QUERY_ENCODED_QUERY) {
            Results.Status(QUERY_ENCODED_QUERY.getStatus)(s"some=$some")
          }
        }

      case GET(p"/encoded%20path%20query" ? q"some=$some") =>
        Action.async {
          controller(QUERY_ENCODED_BOTH) {
            Results.Status(QUERY_ENCODED_BOTH.getStatus)(s"some=$some")
          }
        }

      case GET(p"/path/$path/param") =>
        Action.async {
          controller(PATH_PARAM) {
            Results.Status(PATH_PARAM.getStatus)(path)
          }
        }

      case POST(p"/created") =>
        Action.async(parser) { request =>
          controller(CREATED) {
            val body: String = request.body.asText.getOrElse("")
            Results.Created(s"created: $body")
          }
        }

      case POST(p"/body-urlencoded") =>
        Action.async(parser) { request =>
          controller(BODY_URLENCODED) {
            val body: Map[String, Seq[String]] = request.body.asFormUrlEncoded.getOrElse(Map.empty)
            Results.Ok(body.toStringAsGroovy)
          }
        }

      case POST(p"/body-multipart") =>
        Action.async(parser) { request =>
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
        Action.async(parser) { request =>
          controller(BODY_JSON) {
            val body: JsValue = request.body.asJson.getOrElse(JsNull)
            Results.Ok(body)
          }
        }
    }
  }
}

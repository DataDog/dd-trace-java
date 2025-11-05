package datadog.trace.instrumentation.play25

import datadog.appsec.api.blocking.Blocking
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.base.HttpServerTest.{
  ServerEndpoint,
  getIG_RESPONSE_HEADER,
  getIG_RESPONSE_HEADER_VALUE
}
import datadog.trace.instrumentation.play25.Util.MapExtensions
import groovy.lang.Closure
import play.api.libs.json.{JsNull, JsValue, Json}
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

class PlayController(implicit ec: ExecutionContext) extends Controller {
  def success() = controller(ServerEndpoint.SUCCESS) { _ =>
    Results.Ok(ServerEndpoint.SUCCESS.getBody)
  }

  def redirect = controller(ServerEndpoint.REDIRECT) { _ =>
    Results.Redirect(ServerEndpoint.REDIRECT.getBody, 302)
  }

  def forwarded = controller(ServerEndpoint.FORWARDED) { request =>
    Results.Status(ServerEndpoint.FORWARDED.getStatus)(
      request.headers.get("X-Forwarded-For").getOrElse("(no header)")
    )
  }

  def errorStatus = controller(ServerEndpoint.ERROR) { _ =>
    Results.Status(ServerEndpoint.ERROR.getStatus)(ServerEndpoint.ERROR.getBody)
  }

  def exception = controller(ServerEndpoint.EXCEPTION) { _ =>
    throw new RuntimeException(ServerEndpoint.EXCEPTION.getBody)
  }

  def customException = controller(ServerEndpoint.CUSTOM_EXCEPTION) { _ =>
    throw Util.createCustomException(ServerEndpoint.CUSTOM_EXCEPTION.getBody)
  }

  def userBlock = controller(ServerEndpoint.USER_BLOCK) { _ =>
    Blocking.forUser("user-to-block").blockIfMatch()
    Results.Ok("should never be reached")
  }

  def query(some: String) = controller(ServerEndpoint.QUERY_PARAM) { _ =>
    Results.Status(ServerEndpoint.QUERY_PARAM.getStatus)(s"some=$some")
  }

  def encodedQuery(some: String) = controller(ServerEndpoint.QUERY_ENCODED_QUERY) { _ =>
    Results.Status(ServerEndpoint.QUERY_ENCODED_QUERY.getStatus)(s"some=$some")
  }

  def encodedPathQuery(some: String) = controller(ServerEndpoint.QUERY_ENCODED_BOTH) { _ =>
    Results.Status(ServerEndpoint.QUERY_ENCODED_BOTH.getStatus)(s"some=$some")
  }

  def notHere = controller(ServerEndpoint.NOT_HERE) { _ =>
    Results.NotFound(ServerEndpoint.NOT_HERE.getBody)
  }

  def pathParam(id: Integer) = controller(ServerEndpoint.PATH_PARAM) { _ =>
    Results.Ok(id.toString)
  }

  def created = controller(ServerEndpoint.CREATED) { request =>
    val body: String = request.body.asText.getOrElse("")
    Results.Created(s"created: $body")
  }

  def bodyUrlencoded = controller(ServerEndpoint.BODY_URLENCODED) { request =>
    val body: Map[String, Seq[String]] = request.body.asFormUrlEncoded.getOrElse(Map.empty)
    Results.Ok(body.toStringAsGroovy)
  }

  def bodyMultipart = controller(ServerEndpoint.BODY_MULTIPART) { request =>
    val body: Map[String, Seq[String]] =
      request.body.asMultipartFormData.map(_.asFormUrlEncoded).getOrElse(Map.empty)
    Results.Ok(body.toStringAsGroovy)
  }

  def bodyJson = controller(ServerEndpoint.BODY_JSON) { request =>
    val body: JsValue = request.body.asJson.getOrElse(JsNull)
    Results.Ok(body)
  }

  private def controller(
      endpoint: ServerEndpoint
  )(block: Request[AnyContent] => Result): Action[AnyContent] = {
    Action.async { request =>
      Future {
        HttpServerTest.controller(
          endpoint,
          new Closure[Result](this) {
            def doCall() =
              block(request).withHeaders((getIG_RESPONSE_HEADER, getIG_RESPONSE_HEADER_VALUE))
          }
        )
      }
    }
  }
}

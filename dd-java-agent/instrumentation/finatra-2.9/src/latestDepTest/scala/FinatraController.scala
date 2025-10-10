import com.twitter.finagle.http.{Request, Response}
import com.twitter.finatra.http.Controller
import com.twitter.util.Future
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint._
import datadog.trace.agent.test.base.HttpServerTest.controller
import groovy.lang.Closure

class FinatraController extends Controller {
  any(SUCCESS.getPath) { request: Request =>
    controller(
      SUCCESS,
      new Closure[Response](null) {
        override def call(): Response = {
          response.ok(SUCCESS.getBody)
        }
      }
    )
  }

  any(FORWARDED.getPath) { request: Request =>
    controller(
      FORWARDED,
      new Closure[Response](null) {
        override def call(): Response = {
          response.ok(request.headerMap.get("x-forwarded-for").get)
        }
      }
    )
  }

  any(ERROR.getPath) { request: Request =>
    controller(
      ERROR,
      new Closure[Response](null) {
        override def call(): Response = {
          response.internalServerError(ERROR.getBody)
        }
      }
    )
  }

  any(QUERY_PARAM.getPath) { request: Request =>
    controller(
      QUERY_PARAM,
      new Closure[Response](null) {
        override def call(): Response = {
          response.ok(QUERY_PARAM.getBody)
        }
      }
    )
  }

  any(QUERY_ENCODED_QUERY.getPath) { request: Request =>
    controller(
      QUERY_ENCODED_QUERY,
      new Closure[Response](null) {
        override def call(): Response = {
          response.ok(QUERY_ENCODED_QUERY.getBody)
        }
      }
    )
  }

  any(QUERY_ENCODED_BOTH.getRawPath) { request: Request =>
    controller(
      QUERY_ENCODED_BOTH,
      new Closure[Response](null) {
        override def call(): Response = {
          response
            .ok(QUERY_ENCODED_BOTH.getBody)
            .header(
              HttpServerTest.getIG_RESPONSE_HEADER,
              HttpServerTest.getIG_RESPONSE_HEADER_VALUE
            )
        }
      }
    )
  }

  any(EXCEPTION.getPath) { request: Request =>
    controller(
      EXCEPTION,
      new Closure[Future[Response]](null) {
        override def call(): Future[Response] = {
          throw new Exception(EXCEPTION.getBody)
        }
      }
    )
  }

  any(REDIRECT.getPath) { request: Request =>
    controller(
      REDIRECT,
      new Closure[Response](null) {
        override def call(): Response = {
          response.found.location(REDIRECT.getBody)
        }
      }
    )
  }
}

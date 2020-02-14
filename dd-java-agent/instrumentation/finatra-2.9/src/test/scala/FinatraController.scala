import com.twitter.finagle.http.{Request, Response}
import com.twitter.finatra.http.Controller
import com.twitter.util.Future
import datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint._
import datadog.trace.agent.test.base.HttpServerTest.controller
import groovy.lang.Closure

class FinatraController extends Controller {
  any(SUCCESS.getPath) { request: Request =>
    controller(SUCCESS, new Closure[Response](null) {
      override def call(): Response = {
        response.ok(SUCCESS.getBody)
      }
    })
  }

  any(ERROR.getPath) { request: Request =>
    controller(ERROR, new Closure[Response](null) {
      override def call(): Response = {
        response.internalServerError(ERROR.getBody)
      }
    })
  }

  any(NOT_FOUND.getPath) { request: Request =>
    controller(NOT_FOUND, new Closure[Response](null) {
      override def call(): Response = {
        response.notFound(NOT_FOUND.getBody)
      }
    })
  }

  any(QUERY_PARAM.getPath) { request: Request =>
    controller(QUERY_PARAM, new Closure[Response](null) {
      override def call(): Response = {
        response.ok(QUERY_PARAM.getBody)
      }
    })
  }

  any(EXCEPTION.getPath) { request: Request =>
    controller(EXCEPTION, new Closure[Future[Response]](null) {
      override def call(): Future[Response] = {
        throw new Exception(EXCEPTION.getBody)
      }
    })
  }

  any(REDIRECT.getPath) { request: Request =>
    controller(REDIRECT, new Closure[Response](null) {
      override def call(): Response = {
        response.found.location(REDIRECT.getBody)
      }
    })
  }
}

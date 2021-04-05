package server

import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint._
import play.api.mvc.{Action, Handler, Results}
import play.api.test.{FakeApplication, TestServer}

object SyncServer {
  val routes: PartialFunction[(String, String), Handler] = {
    case ("GET", "/success") =>
      Action { request =>
        HttpServerTest.controller(
          SUCCESS,
          new ControllerClosureAdapter(
            Results.Status(SUCCESS.getStatus).apply(SUCCESS.getBody)
          )
        )
      }
    case ("GET", "/forwarded") =>
      Action { request =>
        HttpServerTest.controller(
          FORWARDED,
          new ControllerClosureAdapter(
            Results.Status(FORWARDED.getStatus).apply(request.remoteAddress)
          )
        )
      }
    case ("GET", "/redirect") =>
      Action { request =>
        HttpServerTest.controller(
          REDIRECT,
          new ControllerClosureAdapter(
            Results.Redirect(REDIRECT.getBody, REDIRECT.getStatus)
          )
        )
      }
    case ("GET", "/query") =>
      Action { request =>
        HttpServerTest.controller(
          QUERY_PARAM,
          new ControllerClosureAdapter(
            Results.Status(QUERY_PARAM.getStatus).apply(QUERY_PARAM.getBody)
          )
        )
      }
    case ("GET", "/error-status") =>
      Action { request =>
        HttpServerTest.controller(
          ERROR,
          new ControllerClosureAdapter(
            Results.Status(ERROR.getStatus).apply(ERROR.getBody)
          )
        )
      }
    case ("GET", "/exception") =>
      Action { request =>
        HttpServerTest.controller(EXCEPTION, new BlockClosureAdapter(() => {
          throw new Exception(EXCEPTION.getBody)
        }))
      }
    case ("GET", "/not-found") =>
      Action { request =>
        HttpServerTest.controller(
          NOT_FOUND,
          new ControllerClosureAdapter(
            Results.Status(NOT_FOUND.getStatus).apply(NOT_FOUND.getBody)
          )
        )
      }
//    case ("GET", "/not-found-propagated") =>
//      Action { request =>
//        HttpServerTest.controller(EXCEPTION, new BlockClosureAdapter(() => {
//          throw new java.lang.Exception("nOT fOuNd")
//        }))
//      }
  }

  def server(port: Int): TestServer = {
    TestServer(
      port,
      FakeApplication(withGlobal = Some(new Settings()), withRoutes = routes)
    )
  }
}

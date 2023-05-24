package server

import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.http.HttpServerOptions
import io.vertx.ext.web.Router
import spock.lang.Ignore

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static server.VertxTestServer.CONFIG_HTTP_SERVER_PORT

@Ignore("This test isn't different from VertxHttpServerTest. Needs rework to meet intent")
class VertxRxHttpServerForkedTest extends VertxHttpServerForkedTest {

  @Override
  protected Class<AbstractVerticle> verticle() {
    return VertxRxWebTestServer
  }

  // TODO not handled without rx instrumentation
  @Override
  boolean testExceptionTag() {
    false
  }

  static class VertxRxWebTestServer extends AbstractVerticle {

    @Override
    void start(final Future<Void> startFuture) {
      final int port = config().getInteger(CONFIG_HTTP_SERVER_PORT)
      final Router router = Router.router(super.@vertx)

      router.route(SUCCESS.path).handler { ctx ->
        controller(SUCCESS) {
          ctx.response().setStatusCode(SUCCESS.status).end(SUCCESS.body)
        }
      }
      router.route(FORWARDED.path).handler { ctx ->
        controller(FORWARDED) {
          ctx.response().setStatusCode(FORWARDED.status).end(ctx.request().getHeader("x-forwarded-for"))
        }
      }
      router.route(QUERY_PARAM.path).handler { ctx ->
        controller(QUERY_PARAM) {
          ctx.response().setStatusCode(QUERY_PARAM.status).end(ctx.request().query())
        }
      }
      router.route("/path/:id/param").handler { ctx ->
        controller(PATH_PARAM) {
          ctx.response().setStatusCode(PATH_PARAM.status).end(ctx.request().getParam("id"))
        }
      }
      router.route(REDIRECT.path).handler { ctx ->
        controller(REDIRECT) {
          ctx.response().setStatusCode(REDIRECT.status).putHeader("location", REDIRECT.body).end()
        }
      }
      router.route(ERROR.path).handler { ctx ->
        controller(ERROR) {
          ctx.response().setStatusCode(ERROR.status).end(ERROR.body)
        }
      }
      router.route(EXCEPTION.path).handler { ctx ->
        controller(EXCEPTION) {
          throw new Exception(EXCEPTION.body)
        }
      }

      super.@vertx.createHttpServer(new HttpServerOptions().setHandle100ContinueAutomatically(true))
        .requestHandler { router.accept(it) }
        .listen(port) { startFuture.complete() }
    }
  }
}

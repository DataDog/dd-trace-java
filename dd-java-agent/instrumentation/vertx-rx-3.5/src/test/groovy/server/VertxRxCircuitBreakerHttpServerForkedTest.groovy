package server

import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import io.vertx.circuitbreaker.CircuitBreakerOptions
import io.vertx.core.Future
import io.vertx.reactivex.circuitbreaker.CircuitBreaker
import io.vertx.reactivex.core.AbstractVerticle
import io.vertx.reactivex.ext.web.Router

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static server.VertxTestServer.CONFIG_HTTP_SERVER_PORT

class VertxRxCircuitBreakerHttpServerForkedTest extends VertxHttpServerForkedTest {

  @Override
  protected Class<AbstractVerticle> verticle() {
    return VertxRxCircuitBreakerWebTestServer
  }

  // TODO not handled without rx instrumentation
  @Override
  boolean testExceptionTag() {
    false
  }

  static class VertxRxCircuitBreakerWebTestServer extends AbstractVerticle {

    @Override
    void start(final Future<Void> startFuture) {
      final int port = config().getInteger(CONFIG_HTTP_SERVER_PORT)
      final Router router = Router.router(super.@vertx)
      final CircuitBreaker breaker =
        CircuitBreaker.create(
        "my-circuit-breaker",
        super.@vertx,
        new CircuitBreakerOptions()
        .setTimeout(-1) // Disable the timeout otherwise it makes each test take this long.
        )

      router.route(SUCCESS.path).handler { ctx ->
        breaker.executeCommand({ future ->
          future.complete(SUCCESS)
        }, { it ->
          if (it.failed()) {
            throw it.cause()
          }
          HttpServerTest.ServerEndpoint endpoint = it.result()
          controller(endpoint) {
            ctx.response().setStatusCode(endpoint.status).end(endpoint.body)
          }
        })
      }
      router.route(FORWARDED.path).handler { ctx ->
        breaker.executeCommand({ future ->
          future.complete(FORWARDED)
        }, { it ->
          if (it.failed()) {
            throw it.cause()
          }
          HttpServerTest.ServerEndpoint endpoint = it.result()
          controller(endpoint) {
            ctx.response().setStatusCode(FORWARDED.status).end(ctx.request().getHeader("x-forwarded-for"))
          }
        })
      }
      router.route(QUERY_ENCODED_BOTH.rawPath).handler { ctx ->
        breaker.executeCommand({ future ->
          future.complete(QUERY_ENCODED_BOTH)
        }, { it ->
          if (it.failed()) {
            throw it.cause()
          }
          HttpServerTest.ServerEndpoint endpoint = it.result()
          controller(endpoint) {
            ctx.response().setStatusCode(endpoint.status).end(endpoint.bodyForQuery(ctx.request().query()))
          }
        })
      }
      router.route(QUERY_ENCODED_QUERY.path).handler { ctx ->
        breaker.executeCommand({ future ->
          future.complete(QUERY_ENCODED_QUERY)
        }, { it ->
          if (it.failed()) {
            throw it.cause()
          }
          HttpServerTest.ServerEndpoint endpoint = it.result()
          controller(endpoint) {
            ctx.response().setStatusCode(endpoint.status).end(endpoint.bodyForQuery(ctx.request().query()))
          }
        })
      }
      router.route(QUERY_PARAM.path).handler { ctx ->
        breaker.executeCommand({ future ->
          future.complete(QUERY_PARAM)
        }, { it ->
          if (it.failed()) {
            throw it.cause()
          }
          HttpServerTest.ServerEndpoint endpoint = it.result()
          controller(endpoint) {
            ctx.response().setStatusCode(endpoint.status).end(ctx.request().query())
          }
        })
      }
      router.route("/path/:id/param").handler { ctx ->
        breaker.executeCommand({ future ->
          future.complete(PATH_PARAM)
        }, { it ->
          if (it.failed()) {
            throw it.cause()
          }
          HttpServerTest.ServerEndpoint endpoint = it.result()
          controller(endpoint) {
            ctx.response().setStatusCode(endpoint.status).end(ctx.request().getParam("id"))
          }
        })
      }
      router.route(REDIRECT.path).handler { ctx ->
        breaker.executeCommand({ future ->
          future.complete(REDIRECT)
        }, {
          if (it.failed()) {
            throw it.cause()
          }
          HttpServerTest.ServerEndpoint endpoint = it.result()
          controller(endpoint) {
            ctx.response().setStatusCode(endpoint.status).putHeader("location", endpoint.body).end()
          }
        })
      }
      router.route(ERROR.path).handler { ctx ->
        breaker.executeCommand({ future ->
          future.complete(ERROR)
        }, {
          if (it.failed()) {
            throw it.cause()
          }
          HttpServerTest.ServerEndpoint endpoint = it.result()
          controller(endpoint) {
            ctx.response().setStatusCode(endpoint.status).end(endpoint.body)
          }
        })
      }
      router.route(EXCEPTION.path).handler { ctx ->
        breaker.executeCommand({ future ->
          future.fail(new RuntimeException(EXCEPTION.body))
        }, {
          try {
            def cause = it.cause()
            controller(EXCEPTION) {
              throw cause
            }
          } catch (Exception ex) {
            ctx.response().setStatusCode(EXCEPTION.status).end(ex.message)
          }
        })
      }

      super.@vertx.createHttpServer()
        .requestHandler { router.accept(it) }
        .listen(port) { startFuture.complete() }
    }
  }

  @Override
  def setup() {
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.THREAD_SEQUENCE)
  }
}

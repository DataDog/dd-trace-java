package server

import datadog.trace.agent.test.base.HttpServer
import play.libs.concurrent.HttpExecution
import play.mvc.Results
import play.routing.RoutingDsl

import java.util.concurrent.CompletableFuture
import java.util.function.Supplier

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

class PlayAsyncServerTest extends PlayServerTest {

  @Override
  HttpServer server() {
    def router =
      new RoutingDsl()
      .GET(SUCCESS.getPath()).routeAsync({
        CompletableFuture.supplyAsync({
          controller(SUCCESS) {
            Results.status(SUCCESS.getStatus(), SUCCESS.getBody())
          }
        }, HttpExecution.defaultContext())
      } as Supplier)
      .GET(FORWARDED.getPath()).routeAsync({
        CompletableFuture.supplyAsync({
          controller(FORWARDED) {
            Results.status(FORWARDED.getStatus(), FORWARDED.getBody()) // cheating
          }
        }, HttpExecution.defaultContext())
      } as Supplier)
      .GET(QUERY_PARAM.getPath()).routeAsync({
        CompletableFuture.supplyAsync({
          controller(QUERY_PARAM) {
            Results.status(QUERY_PARAM.getStatus(), QUERY_PARAM.getBody()) // cheating
          }
        }, HttpExecution.defaultContext())
      } as Supplier)
      .GET(QUERY_ENCODED_QUERY.getPath()).routeAsync({
        CompletableFuture.supplyAsync({
          controller(QUERY_ENCODED_QUERY) {
            Results.status(QUERY_ENCODED_QUERY.getStatus(), QUERY_ENCODED_QUERY.getBody()) // cheating
          }
        }, HttpExecution.defaultContext())
      } as Supplier)
      .GET(QUERY_ENCODED_BOTH.getRawPath()).routeAsync({
        CompletableFuture.supplyAsync({
          controller(QUERY_ENCODED_BOTH) {
            Results.status(QUERY_ENCODED_BOTH.getStatus(), QUERY_ENCODED_BOTH.getBody()).
              withHeader(IG_RESPONSE_HEADER, IG_RESPONSE_HEADER_VALUE) // cheating
          }
        }, HttpExecution.defaultContext())
      } as Supplier)
      .GET(REDIRECT.getPath()).routeAsync({
        CompletableFuture.supplyAsync({
          controller(REDIRECT) {
            Results.found(REDIRECT.getBody())
          }
        }, HttpExecution.defaultContext())
      } as Supplier)
      .GET(ERROR.getPath()).routeAsync({
        CompletableFuture.supplyAsync({
          controller(ERROR) {
            Results.status(ERROR.getStatus(), ERROR.getBody())
          }
        }, HttpExecution.defaultContext())
      } as Supplier)
      .GET(EXCEPTION.getPath()).routeAsync({
        CompletableFuture.supplyAsync({
          controller(EXCEPTION) {
            throw new Exception(EXCEPTION.getBody())
          }
        }, HttpExecution.defaultContext())
      } as Supplier)

    return new PlayHttpServer(router.build())
  }
}

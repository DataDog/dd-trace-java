package server

import datadog.trace.agent.test.base.HttpServer
import play.BuiltInComponents
import play.libs.concurrent.HttpExecution
import play.mvc.Results
import play.routing.RoutingDsl
import spock.lang.Shared

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
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
  @Shared
  def executor

  def cleanupSpec() {
    executor.shutdown()
  }

  @Override
  HttpServer server() {
    executor = Executors.newCachedThreadPool()
    def execContext = HttpExecution.fromThread(executor)
    return new PlayHttpServer({ BuiltInComponents components ->
      RoutingDsl.fromComponents(components)
        .GET(SUCCESS.getPath()).routeAsync({
          CompletableFuture.supplyAsync({
            controller(SUCCESS) {
              Results.status(SUCCESS.getStatus(), SUCCESS.getBody())
            }
          }, execContext)
        } as Supplier)
        .GET(FORWARDED.getPath()).routeAsync({
          CompletableFuture.supplyAsync({
            controller(FORWARDED) {
              Results.status(FORWARDED.getStatus(), FORWARDED.getBody()) // cheating
            }
          }, execContext)
        } as Supplier)
        .GET(QUERY_PARAM.getPath()).routeAsync({
          CompletableFuture.supplyAsync({
            controller(QUERY_PARAM) {
              Results.status(QUERY_PARAM.getStatus(), QUERY_PARAM.getBody()) // cheating
            }
          }, execContext)
        } as Supplier)
        .GET(QUERY_ENCODED_QUERY.getPath()).routeAsync({
          CompletableFuture.supplyAsync({
            controller(QUERY_ENCODED_QUERY) {
              Results.status(QUERY_ENCODED_QUERY.getStatus(), QUERY_ENCODED_QUERY.getBody()) // cheating
            }
          }, execContext)
        } as Supplier)
        .GET(QUERY_ENCODED_BOTH.getRawPath()).routeAsync({
          CompletableFuture.supplyAsync({
            controller(QUERY_ENCODED_BOTH) {
              Results.status(QUERY_ENCODED_BOTH.getStatus(), QUERY_ENCODED_BOTH.getBody()).
                withHeader(IG_RESPONSE_HEADER, IG_RESPONSE_HEADER_VALUE) // cheating
            }
          }, execContext)
        } as Supplier)
        .GET(REDIRECT.getPath()).routeAsync({
          CompletableFuture.supplyAsync({
            controller(REDIRECT) {
              Results.found(REDIRECT.getBody())
            }
          }, execContext)
        } as Supplier)
        .GET(ERROR.getPath()).routeAsync({
          CompletableFuture.supplyAsync({
            controller(ERROR) {
              Results.status(ERROR.getStatus(), ERROR.getBody())
            }
          }, execContext)
        } as Supplier)
        .GET(EXCEPTION.getPath()).routeAsync({
          CompletableFuture.supplyAsync({
            controller(EXCEPTION) {
              throw new RuntimeException(EXCEPTION.getBody())
            }
          }, execContext)
        } as Supplier)
        .build()
    })
  }
}

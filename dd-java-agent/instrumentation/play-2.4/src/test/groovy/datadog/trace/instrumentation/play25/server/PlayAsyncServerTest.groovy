package datadog.trace.instrumentation.play25.server

import datadog.trace.agent.test.base.HttpServer
import groovy.transform.CompileStatic
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
  @CompileStatic
  HttpServer server() {
    new PlayHttpServer(PlayRouters.async(HttpExecution.defaultContext()))
  }
}

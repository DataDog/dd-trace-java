package datadog.trace.instrumentation.play26.server.latestdep

import datadog.trace.agent.test.base.HttpServer
import datadog.trace.instrumentation.play26.server.PlayServerWithErrorHandlerTest
import datadog.trace.instrumentation.play26.server.TestHttpErrorHandler
import play.api.BuiltInComponents
import play.routing.Router
import spock.lang.Shared

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.Function

class PlayAsyncServerScalaWithErrorHandlerTest extends PlayServerWithErrorHandlerTest {
  @Shared
  ExecutorService executor

  def cleanupSpec() {
    executor.shutdown()
  }

  @Override
  HttpServer server() {
    executor = Executors.newCachedThreadPool()

    Function<BuiltInComponents, Router> f = { play.api.BuiltInComponents it ->
      PlayRoutersScala$.MODULE$.async(executor, it)
    } as Function<BuiltInComponents, Router>
    new PlayHttpServerScala(f, new TestHttpErrorHandler())
  }

  @Override
  Map<String, ?> expectedIGPathParams() {
    ['0': '123']
  }
}

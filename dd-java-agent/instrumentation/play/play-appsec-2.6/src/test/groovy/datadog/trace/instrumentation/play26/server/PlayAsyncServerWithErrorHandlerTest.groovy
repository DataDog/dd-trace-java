package datadog.trace.instrumentation.play26.server

import datadog.trace.agent.test.base.HttpServer
import spock.lang.Shared

import java.util.concurrent.Executors

class PlayAsyncServerWithErrorHandlerTest extends AbstractPlayServerWithErrorHandlerTest {
  @Shared
  def executor

  def cleanupSpec() {
    executor.shutdown()
  }

  @Override
  HttpServer server() {
    executor = Executors.newCachedThreadPool()
    return new PlayHttpServer(PlayRouters.&async.curry(executor), new TestHttpErrorHandler())
  }
}

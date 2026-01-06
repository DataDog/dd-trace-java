package datadog.trace.instrumentation.play26.server

import datadog.trace.agent.test.base.HttpServer
import groovy.transform.CompileStatic
import spock.lang.Shared

import java.util.concurrent.Executors

class PlayAsyncServerTest extends AbstractPlayServerTest {
  @Shared
  def executor

  def cleanupSpec() {
    executor.shutdown()
  }

  @CompileStatic
  @Override
  HttpServer server() {
    executor = Executors.newCachedThreadPool()
    return new PlayHttpServer(PlayRouters.&async.curry(executor))
  }
}

package datadog.trace.instrumentation.play25.server

import datadog.trace.agent.test.base.HttpServer
import datadog.trace.instrumentation.play25.PlayRoutersScala
import groovy.transform.CompileStatic
import spock.lang.Shared

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PlayScalaAsyncServerTest extends PlayServerTest {
  @Shared
  ExecutorService executor

  def cleanupSpec() {
    executor.shutdown()
  }

  @Override
  @CompileStatic
  HttpServer server() {
    executor = Executors.newCachedThreadPool()
    new PlayHttpServer(PlayRoutersScala.async(executor).asJava())
  }

  @Override
  Map<String, ?> expectedIGPathParams() {
    ['0': '123']
  }
}

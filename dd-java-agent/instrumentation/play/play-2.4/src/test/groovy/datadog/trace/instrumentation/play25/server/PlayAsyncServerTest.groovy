package datadog.trace.instrumentation.play25.server

import datadog.trace.agent.test.base.HttpServer
import groovy.transform.CompileStatic
import play.libs.concurrent.HttpExecution

class PlayAsyncServerTest extends PlayServerTest {

  @Override
  @CompileStatic
  HttpServer server() {
    new PlayHttpServer(PlayRouters.async(HttpExecution.defaultContext()))
  }
}

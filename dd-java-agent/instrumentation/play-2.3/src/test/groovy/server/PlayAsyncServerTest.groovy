package server

import datadog.trace.agent.test.base.HttpServer

class PlayAsyncServerTest extends PlayServerTest {

  @Override
  HttpServer server() {
    return new AsyncServer()
  }
}

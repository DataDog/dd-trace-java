package server

import datadog.trace.agent.test.base.HttpServer
import play.routing.Router
import play.server.Server

import java.util.concurrent.TimeoutException

class PlayHttpServer implements HttpServer {
  final Router router
  def server
  def port

  PlayHttpServer(Router router) {
    this.router = router
  }

  @Override
  void start() throws TimeoutException {
    server = Server.forRouter(router, 0)
    port = server.httpPort()
  }

  @Override
  void stop() {
    server.stop()
  }

  @Override
  URI address() {
    return new URI("http://localhost:$port/")
  }
}

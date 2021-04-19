package server

import datadog.trace.agent.test.base.HttpServer
import play.BuiltInComponents
import play.Mode
import play.routing.Router
import play.server.Server

import java.util.concurrent.TimeoutException
import java.util.function.Function

class PlayHttpServer implements HttpServer {
  final Function<BuiltInComponents, Router> router
  def server
  def port

  PlayHttpServer(Function<BuiltInComponents, Router> router) {
    this.router = router
  }

  @Override
  void start() throws TimeoutException {
    server = Server.forRouter(Mode.TEST, 0, router)
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

package server

import datadog.trace.agent.test.base.HttpServer
import ratpack.test.embed.EmbeddedApp

class RatpackServer implements HttpServer {
  final EmbeddedApp server

  RatpackServer(EmbeddedApp server) {
    this.server = server
    assert !server.server.running
  }

  @Override
  void start() {
    server.server.start()
  }

  @Override
  void stop() {
    server.close()
  }

  @Override
  URI address() {
    return server.address
  }
}

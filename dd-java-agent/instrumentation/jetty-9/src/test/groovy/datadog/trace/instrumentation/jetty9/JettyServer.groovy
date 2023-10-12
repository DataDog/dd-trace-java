package datadog.trace.instrumentation.jetty9

import datadog.trace.agent.test.base.HttpServer
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.AbstractHandler

class JettyServer implements HttpServer {
  def port = 0
  final server = new Server(0) // select random open port

  JettyServer(AbstractHandler handler) {
    server.setHandler(handler)
    server.addBean(TestHandler.errorHandler)
  }

  @Override
  void start() {
    server.start()
    port = server.connectors[0].localPort
    assert port > 0
  }

  @Override
  void stop() {
    server.stop()
  }

  @Override
  URI address() {
    new URI("http://localhost:$port/")
  }

  @Override
  String toString() {
    this.class.name
  }
}

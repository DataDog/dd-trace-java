package datadog.trace.instrumentation.jersey2

import datadog.trace.agent.test.base.HttpServer
import datadog.trace.instrumentation.jersey2.iast.IastResource
import org.eclipse.jetty.server.Server
import org.glassfish.jersey.jackson.JacksonFeature
import org.glassfish.jersey.jetty.JettyHttpContainerFactory
import org.glassfish.jersey.media.multipart.MultiPartFeature
import org.glassfish.jersey.server.ResourceConfig

import java.util.concurrent.TimeoutException

class JettyServer implements HttpServer {
  final Server server
  int port = 0

  JettyServer() {
    ResourceConfig rc = new ResourceConfig()
    rc.register(Jersey2JettyTest.SimpleExceptionMapper)
    rc.register(ServiceResource)
    rc.register(ResponseServerFilter)
    rc.register(MultiPartFeature)
    rc.register(JacksonFeature)
    rc.register(IastResource)

    server = JettyHttpContainerFactory.createServer(new URI("http://localhost:0"), rc, false)
  }

  @Override
  void start() throws TimeoutException {
    server.start()

    def transport = server.connectors.first().transport
    if (transport.respondsTo('getSocket')) {
      port = transport.socket.localPort
    } else {
      port = transport.localAddress.port
    }
  }

  @Override
  void stop() {
    server.stop()
  }

  @Override
  URI address() {
    new URI("http://localhost:$port/")
  }
}

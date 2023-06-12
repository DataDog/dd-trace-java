package datadog.trace.instrumentation.jersey2

import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import org.eclipse.jetty.server.Server
import org.glassfish.jersey.jackson.JacksonFeature
import org.glassfish.jersey.jetty.JettyHttpContainerFactory
import org.glassfish.jersey.media.multipart.MultiPartFeature
import org.glassfish.jersey.server.ResourceConfig

import javax.ws.rs.NotFoundException
import javax.ws.rs.core.Response
import javax.ws.rs.ext.ExceptionMapper
import java.util.concurrent.TimeoutException

class Jersey2JettyTest extends HttpServerTest<JettyServer> {

  static class JettyServer implements HttpServer {
    final Server server
    int port = 0

    JettyServer() {
      ResourceConfig rc = new ResourceConfig()
      rc.register(SimpleExceptionMapper)
      rc.register(ServiceResource)
      rc.register(ResponseServerFilter)
      rc.register(MultiPartFeature)
      rc.register(JacksonFeature)

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

  @Override
  HttpServer server() {
    new JettyServer()
  }

  @Override
  String component() {
    'jetty-server'
  }

  @Override
  String expectedOperationName() {
    'servlet.request'
  }

  @Override
  boolean testBlocking() {
    true
  }

  @Override
  boolean testRequestBody() {
    false
  }

  @Override
  boolean testBodyUrlencoded() {
    true
  }

  @Override
  boolean testBodyMultipart() {
    true
  }

  @Override
  boolean testBodyJson() {
    true
  }

  @Override
  String testPathParam() {
    '/path/?/param'
  }

  Map<String, ?> expectedIGPathParams() {
    [id: ['123']]
  }

  @Override
  boolean testBadUrl() {
    false
  }

  static class SimpleExceptionMapper implements ExceptionMapper<Throwable> {

    @Override
    Response toResponse(Throwable exception) {
      if (exception instanceof NotFoundException) {
        return exception.getResponse()
      }
      Response.status(500).entity(exception.message).build()
    }
  }
}

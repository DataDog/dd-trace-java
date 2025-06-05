import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.jetty.JettyService
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import test.JettyServer
import test.TestHandler

import java.util.concurrent.TimeoutException

class ArmeriaJetty9ServerTest extends HttpServerTest<ArmeriaServer> {
  class ArmeriaServer implements HttpServer {
    def port
    def server = Server.builder()
    .serviceUnder("/", JettyService.of(new JettyServer(TestHandler.INSTANCE).server))
    .build()

    @Override
    void start() throws TimeoutException {
      server.start().get()
      port = server.activeLocalPort()
    }

    @Override
    void stop() {
      server?.stop()?.get()
    }

    @Override
    URI address() {
      URI.create("http://localhost:$port/")
    }
  }

  @Override
  HttpServer server() {
    new ArmeriaServer()
  }


  @Override
  String component() {
    return "jetty-server"
  }

  @Override
  String expectedOperationName() {
    "servlet.request"
  }

  @Override
  protected boolean enabledFinishTimingChecks() {
    true
  }

  @Override
  boolean testEncodedQuery() {
    false
  }

  @Override
  boolean testExceptionBody() {
    false
  }

  @Override
  boolean testBodyUrlencoded() {
    true
  }

  @Override
  boolean testRequestBody() {
    true
  }

  @Override
  boolean testRequestBodyISVariant() {
    true
  }

  @Override
  boolean testBodyMultipart() {
    true
  }

  @Override
  boolean testBlocking() {
    false
  }

  @Override
  boolean testUserBlocking() {
    false
  }

  @Override
  boolean testBlockingOnResponse() {
    false
  }

  @Override
  boolean hasExtraErrorInformation() {
    true
  }

  @Override
  boolean testEncodedPath() {
    return false
  }
}

import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.jetty.JettyService
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.instrumentation.servlet5.TestServlet5

import java.util.concurrent.TimeoutException

class ArmeriaJetty11ServerTest extends HttpServerTest<ArmeriaServer> {
  class ArmeriaServer implements HttpServer {
    def port
    def server = Server.builder()
    .serviceUnder("/", JettyService.of(new JettyServer(JettyServer.servletHandler(TestServlet5)).server))
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
      URI.create("http://localhost:$port/context-path/")
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
  Map<String, Serializable> expectedExtraServerTags(HttpServerTest.ServerEndpoint endpoint) {
    ['servlet.context': '/context-path', 'servlet.path': endpoint.path]
  }

  @Override
  String expectedServiceName() {
    'context-path'
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

  @Override
  boolean testSessionId() {
    true
  }

  @Override
  boolean testWebsockets() {
    false
  }
}

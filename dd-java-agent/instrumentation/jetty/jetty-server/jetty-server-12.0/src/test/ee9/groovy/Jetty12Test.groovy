import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.instrumentation.servlet5.TestServlet5
import org.eclipse.jetty.server.Server

class Jetty12Test extends HttpServerTest<Server> implements TestingGenericHttpNamingConventions.ServerV1 {
  @Override
  HttpServer server() {
    new JettyServer(JettyServer.servletHandler(TestServlet5), useWebsocketPojoEndpoint())
  }

  @Override
  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    ['servlet.context': '/context-path', 'servlet.path': endpoint.path]
  }

  @Override
  String component() {
    'jetty-server'
  }

  @Override
  String expectedOperationName() {
    operation()
  }

  @Override
  String expectedServiceName() {
    'context-path'
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
  boolean hasExtraErrorInformation() {
    true
  }

  @Override
  boolean testExceptionBody() {
    false
  }

  protected boolean useWebsocketPojoEndpoint() {
    false
  }
}

class Jetty12PojoWebsocketTest extends Jetty12Test {
  protected boolean useWebsocketPojoEndpoint() {
    // advices for pojo won't apply for latest alpha 12.1.+. It has to be adapted once jetty codebase will be stable
    !isLatestDepTest
  }
}

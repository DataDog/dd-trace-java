import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.instrumentation.servlet5.HtmlRumServlet
import datadog.trace.instrumentation.servlet5.TestServlet5
import datadog.trace.instrumentation.servlet5.XmlRumServlet
import org.eclipse.jetty.ee10.servlet.ServletContextHandler
import org.eclipse.jetty.server.Server

class Jetty12Test extends HttpServerTest<Server> implements TestingGenericHttpNamingConventions.ServerV0 {
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
  boolean testExceptionBody() {
    false
  }

  @Override
  boolean hasExtraErrorInformation() {
    true
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

class Jetty12RumInjectionForkedTest extends Jetty12Test {
  @Override
  boolean testRumInjection() {
    true
  }

  @Override
  HttpServer server() {
    ServletContextHandler handler = JettyServer.servletHandler(TestServlet5)
    handler.addServlet(HtmlRumServlet, "/gimme-html")
    handler.addServlet(XmlRumServlet, "/gimme-xml")
    new JettyServer(handler, useWebsocketPojoEndpoint())
  }
}

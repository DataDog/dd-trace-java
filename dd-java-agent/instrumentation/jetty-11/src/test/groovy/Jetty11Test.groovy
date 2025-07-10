import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.instrumentation.servlet5.HtmlRumServlet
import datadog.trace.instrumentation.servlet5.TestServlet5
import datadog.trace.instrumentation.servlet5.XmlRumServlet
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Server

abstract class Jetty11Test extends HttpServerTest<Server> {
  @Override
  HttpServer server() {
    new JettyServer(handler(), useWebsocketPojoEndpoint())
  }

  protected Handler handler() {
    JettyServer.servletHandler(TestServlet5)
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
  protected boolean enabledFinishTimingChecks() {
    true
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
    true
  }

  @Override
  boolean testUserBlocking() {
    true
  }

  @Override
  boolean testBlockingOnResponse() {
    true
  }

  @Override
  boolean hasExtraErrorInformation() {
    true
  }

  @Override
  boolean testSessionId() {
    true
  }

  protected boolean useWebsocketPojoEndpoint() {
    false
  }
}

class Jetty11V0ForkedTest extends Jetty11Test implements TestingGenericHttpNamingConventions.ServerV0 {
}

class Jetty11V1ForkedTest extends Jetty11Test implements TestingGenericHttpNamingConventions.ServerV1 {
  protected boolean useWebsocketPojoEndpoint() {
    true
  }
}

class JettyRumInjectionForkedTest extends Jetty11V0ForkedTest {
  @Override
  boolean testRumInjection() {
    true
  }

  @Override
  protected Handler handler() {
    def handler = JettyServer.servletHandler(TestServlet5)
    handler.addServlet(HtmlRumServlet, "/gimme-html")
    handler.addServlet(XmlRumServlet, "/gimme-xml")
    handler
  }
}

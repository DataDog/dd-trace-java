import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.instrumentation.servlet3.TestServlet3
import org.eclipse.jetty.server.Server

import javax.servlet.Servlet

abstract class Jetty12Test extends HttpServerTest<Server> implements TestingGenericHttpNamingConventions.ServerV0 {
  @Override
  HttpServer server() {
    new JettyServer(JettyServer.servletHandler(servletClass()))
  }

  protected abstract Class<Servlet> servletClass()

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
}

class Jetty12SyncTest extends Jetty12Test {
  @Override
  protected Class<Servlet> servletClass() {
    TestServlet3.Sync
  }
}

class Jetty12AsyncTest extends Jetty12Test {
  @Override
  protected Class<Servlet> servletClass() {
    TestServlet3.Async
  }
  // See JettyServlet3Test
  @Override
  boolean testException() {
    false
  }

  @Override
  boolean testTimeout() {
    true
  }
}

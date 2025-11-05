package datadog.trace.instrumentation.jetty10

import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.AbstractHandler
import test.JettyServer
import test.TestHandler

abstract class Jetty10Test extends HttpServerTest<Server> {

  @Override
  HttpServer server() {
    new JettyServer(handler(), useWebsocketPojoEndpoint())
  }

  AbstractHandler handler() {
    TestHandler.INSTANCE
  }

  @Override
  String component() {
    "jetty-server"
  }

  @Override
  String expectedOperationName() {
    operation()
  }

  protected boolean useWebsocketPojoEndpoint() {
    // only supported in jetty 10+
    isLatestDepTest
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
  boolean testUserBlocking() {
    true
  }

  @Override
  boolean testBlocking() {
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
  boolean testBodyMultipart() {
    true
  }

  @Override
  boolean testSessionId() {
    true
  }

  @Override
  boolean testWebsockets() {
    return super.testWebsockets() && (getServer() as JettyServer).websocketAvailable
  }
}

class Jetty10V0ForkedTest extends Jetty10Test implements TestingGenericHttpNamingConventions.ServerV0 {
}

class Jetty10V1ForkedTest extends Jetty10Test implements TestingGenericHttpNamingConventions.ServerV1 {
  @Override
  protected boolean useWebsocketPojoEndpoint() {
    false
  }
}

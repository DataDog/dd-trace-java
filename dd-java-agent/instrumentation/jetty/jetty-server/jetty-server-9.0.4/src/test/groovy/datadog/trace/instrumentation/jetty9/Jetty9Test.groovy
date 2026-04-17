package datadog.trace.instrumentation.jetty9

import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.AbstractHandler
import test.JettyServer
import test.TestHandler

abstract class Jetty9Test extends HttpServerTest<Server> {

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
    false
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
    // jetty-appsec-8.1.3 covers [8.1.3, 9.2.0.RC0) which includes Jetty 9.0.x.
    // Its extractContentParameters() advice calls ParameterCollector.put(String, String)
    // which does not exist in Jetty 9.0.x → HTTP 500 on multipart requests.
    false
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

class Jetty9V0ForkedTest extends Jetty9Test implements TestingGenericHttpNamingConventions.ServerV0 {
}

class Jetty9V1ForkedTest extends Jetty9Test implements TestingGenericHttpNamingConventions.ServerV1 {
}

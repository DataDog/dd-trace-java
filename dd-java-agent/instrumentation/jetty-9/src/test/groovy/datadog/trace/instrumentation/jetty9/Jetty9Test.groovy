package datadog.trace.instrumentation.jetty9

import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.AbstractHandler

abstract class Jetty9Test extends HttpServerTest<Server> {

  @Override
  HttpServer server() {
    new JettyServer(handler())
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
}

class Jetty9V0ForkedTest extends Jetty9Test implements TestingGenericHttpNamingConventions.ServerV0 {
}

class Jetty9V1ForkedTest extends Jetty9Test implements TestingGenericHttpNamingConventions.ServerV1 {
}

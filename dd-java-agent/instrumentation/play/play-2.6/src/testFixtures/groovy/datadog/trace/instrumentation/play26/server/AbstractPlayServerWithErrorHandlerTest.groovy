package datadog.trace.instrumentation.play26.server

import datadog.trace.agent.test.base.HttpServer

abstract class AbstractPlayServerWithErrorHandlerTest extends AbstractPlayServerTest {
  @Override
  HttpServer server() {
    new PlayHttpServer(PlayRouters.&sync, new TestHttpErrorHandler())
  }

  @Override
  boolean testExceptionBody() {
    true
  }
}

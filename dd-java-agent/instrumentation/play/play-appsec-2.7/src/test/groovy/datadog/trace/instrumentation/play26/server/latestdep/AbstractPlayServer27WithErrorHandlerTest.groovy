package datadog.trace.instrumentation.play26.server.latestdep

import datadog.trace.agent.test.base.HttpServer
import datadog.trace.instrumentation.play26.server.AbstractPlayServerWithErrorHandlerTest
import datadog.trace.instrumentation.play26.server.PlayHttpServer
import datadog.trace.instrumentation.play26.server.TestHttpErrorHandler

abstract class AbstractPlayServer27WithErrorHandlerTest extends AbstractPlayServerWithErrorHandlerTest {
  @Override
  HttpServer server() {
    new PlayHttpServer(Play27Routers.&sync, new TestHttpErrorHandler())
  }
}

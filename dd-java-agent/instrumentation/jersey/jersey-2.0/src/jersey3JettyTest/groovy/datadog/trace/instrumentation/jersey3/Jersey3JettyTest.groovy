package datadog.trace.instrumentation.jersey3

import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper

class Jersey3JettyTest extends HttpServerTest<JettyServer> {

  @Override
  boolean testResponseBodyJson() {
    return true
  }

  @Override
  HttpServer server() {
    new JettyServer()
  }

  @Override
  String component() {
    'jetty-server'
  }

  @Override
  String expectedOperationName() {
    'servlet.request'
  }

  @Override
  protected boolean enabledFinishTimingChecks() {
    true
  }

  @Override
  boolean testBlocking() {
    true
  }

  @Override
  boolean testRequestBody() {
    false
  }

  @Override
  boolean testBodyUrlencoded() {
    true
  }

  @Override
  boolean testBodyMultipart() {
    true
  }

  @Override
  boolean testBodyJson() {
    true
  }

  @Override
  boolean testBlockingOnResponse() {
    true
  }

  @Override
  String testPathParam() {
    '/path/?/param'
  }

  Map<String, ?> expectedIGPathParams() {
    [id: ['123']]
  }

  @Override
  boolean testBadUrl() {
    false
  }

  static class SimpleExceptionMapper implements ExceptionMapper<Throwable> {

    @Override
    Response toResponse(Throwable exception) {
      if (exception instanceof NotFoundException) {
        return exception.getResponse()
      }
      Response.status(500).entity(exception.message).build()
    }
  }
}

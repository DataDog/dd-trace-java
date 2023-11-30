package datadog.trace.instrumentation.jersey2

import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest

import javax.ws.rs.NotFoundException
import javax.ws.rs.core.Response
import javax.ws.rs.ext.ExceptionMapper

class Jersey2JettyTest extends HttpServerTest<JettyServer> {

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

package datadog.trace.instrumentation.sparkjava

import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.agent.test.utils.PortUtils

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM

abstract class SparkJavaTest extends HttpServerTest<Object> {

  @Override
  HttpServer server() {
    def socket = PortUtils.randomOpenSocket()
    def port = socket.localPort
    socket.close()
    return new SparkJavaServer(port)
  }

  @Override
  String component() {
    // Server span is created by Jetty instrumentation; SparkJava adds http.route
    return "jetty-server"
  }

  @Override
  String expectedOperationName() {
    return operation()
  }

  @Override
  boolean testExceptionBody() {
    false
  }

  @Override
  boolean testNotFound() {
    false
  }

  @Override
  String testPathParam() {
    "/path/:id/param"
  }

  @Override
  boolean hasExtraErrorInformation() {
    true
  }

  @Override
  boolean testRequestBody() {
    true
  }

  @Override
  boolean testBodyUrlencoded() {
    // SparkJava's custom MatcherFilter pipeline doesn't trigger the
    // Servlet-level body object callback that parses urlencoded bodies
    // into the 'request.body.converted' IG tag. Raw request.body works.
    false
  }

  @Override
  boolean testBlocking() {
    // SparkJava uses a custom JettyHandler/MatcherFilter pipeline that
    // bypasses the standard Servlet filter chain where AppSec blocking
    // hooks are installed. Blocking requires SparkJava-specific AppSec
    // instrumentation which is not yet implemented.
    false
  }

  @Override
  boolean testBlockingOnResponse() {
    false
  }

  @Override
  protected boolean enabledFinishTimingChecks() {
    true
  }

  @Override
  Serializable expectedServerSpanRoute(ServerEndpoint endpoint) {
    switch (endpoint) {
      case PATH_PARAM:
        return testPathParam()
      default:
        return endpoint.path
    }
  }

  @Override
  boolean hasDecodedResource() {
    true
  }

  @Override
  boolean redirectHasBody() {
    true
  }

  @Override
  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    Collections.emptyMap()
  }
}

class SparkJavaV0ForkedTest extends SparkJavaTest implements TestingGenericHttpNamingConventions.ServerV0 {
}

class SparkJavaV1ForkedTest extends SparkJavaTest implements TestingGenericHttpNamingConventions.ServerV1 {
}

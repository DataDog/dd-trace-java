import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.instrumentation.spray.SprayHttpServerDecorator

abstract class SprayServerTest extends HttpServerTest<SprayHttpTestWebServer> {

  @Override
  HttpServer server() {
    SprayHttpTestWebServer server = new SprayHttpTestWebServer()
    server.start()
    return server
  }

  @Override
  void stopServer(SprayHttpTestWebServer sprayHttpTestWebServer) {
    sprayHttpTestWebServer.stop()
  }

  @Override
  String component() {
    return SprayHttpServerDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    return operation()
  }

  @Override
  boolean testExceptionBody() {
    // Todo: Response{protocol=http/1.1, code=500, message=Internal Server Error, url=...}
    false
  }

  @Override
  boolean testBadUrl() {
    false
  }

  @Override
  boolean hasExtraErrorInformation() {
    true
  }

  @Override
  boolean hasPeerInformation() {
    return false
  }

  @Override
  boolean hasForwardedIP() {
    return true
  }

  @Override
  boolean hasPlusEncodedSpaces() {
    true
  }

  @Override
  def setup() {
  }
}

class SprayServerV0ForkedTest extends SprayServerTest {
  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return null
  }

  @Override
  String operation() {
    return "spray-http.request"
  }
}

class SprayServerV1ForkedTest extends SprayServerTest implements TestingGenericHttpNamingConventions.ServerV1 {
}

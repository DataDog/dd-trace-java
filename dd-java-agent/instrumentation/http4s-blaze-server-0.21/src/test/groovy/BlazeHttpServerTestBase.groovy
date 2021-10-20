import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest

abstract class BlazeHttpServerTestBase extends HttpServerTest<BlazeHttpTestWebServer> {

  @Override
  HttpServer server() {
    BlazeHttpTestWebServer server = new BlazeHttpTestWebServer()
    server.start()
    return server
  }

  @Override
  void stopServer(BlazeHttpTestWebServer http4sHttpTestWebServer) {
    http4sHttpTestWebServer.stop()
  }

  @Override
  String component() {
    return "http4s-http-server"
  }

  @Override
  String expectedOperationName() {
    return "http4s-http.request"
  }

  @Override
  boolean testExceptionBody() {
    // Todo: CHECK THIS OUT
    false
  }

  @Override
  boolean testForwarded() {
    // Todo: did not see a way to forward
    false
  }

  @Override
  String testPathParam() {
    "/path/?/param"
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
    return false
  }
}

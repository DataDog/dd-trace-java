import datadog.trace.agent.test.base.HttpClientTest
import spock.lang.Timeout

@Timeout(5)
abstract class BlazeHttpClientTestBase extends HttpClientTest {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.integration.experimental.http4s.enabled", "true")
  }

  @Override
  CharSequence component() {
    return "http4s-client"
  }

  @Override
  String expectedOperationName() {
    return "http.request"
  }

  @Override
  boolean testRedirects() {
    // Creates a span for redirects
    false
  }

  @Override
  boolean testConnectionFailure() {
    // connection failures do not create spans
    false
  }

  @Override
  boolean testRemoteConnection() {
    // connection failures do not create spans
    false
  }
}

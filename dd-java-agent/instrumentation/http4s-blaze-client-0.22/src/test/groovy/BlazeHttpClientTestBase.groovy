import datadog.trace.agent.test.base.HttpClientTest
import spock.lang.Timeout

@Timeout(5)
abstract class BlazeHttpClientTestBase extends HttpClientTest {
  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    int result = BlazeClientHelper.doRequest(method, uri, headers)

    callback?.call()

    result
  }

  @Override
  CharSequence component() {
    return "http4s-http-client"
  }

  @Override
  String expectedOperationName() {
    return "http4s-http.request"
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

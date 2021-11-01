import datadog.trace.agent.test.base.HttpClientTest
import spock.lang.Timeout

@Timeout(5)
abstract class BlazeHttpClientTimedTest extends BlazeHttpClientTestBase {
  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    BlazeClientHelper.doTimedRequest(method, uri, headers, callback)
  }
}

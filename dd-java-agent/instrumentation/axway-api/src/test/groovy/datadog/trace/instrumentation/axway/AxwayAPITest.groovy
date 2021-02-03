package datadog.trace.instrumentation.axway

import datadog.trace.agent.test.base.HttpClientTest
import org.junit.Ignore
import spock.lang.Shared
import spock.lang.Timeout

@Ignore
@Timeout(5)
class AxwayAPITest extends HttpClientTest {

  //@Shared
  //Client client = Client.create()

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, Closure callback) {

    return 200
  }

  @Override
  String component() {
    return AxwayHTTPPluginDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    return ""
  }

  boolean testCircularRedirects() {
    false
  }
}

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

//  def setupSpec() {
//    client.setConnectTimeout(CONNECT_TIMEOUT_MS)
//    client.setReadTimeout(READ_TIMEOUT_MS)
//    // Add filters to ensure spans aren't duplicated.
//    client.addFilter(new LoggingFilter())
//    client.addFilter(new GZIPContentEncodingFilter())
//  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, Closure callback) {
//    def resource = client.resource(uri).requestBuilder
//    headers.each { resource.header(it.key, it.value) }
//    def body = BODY_METHODS.contains(method) ? "" : null
//    ClientResponse response = resource.method(method, ClientResponse, body)
//    callback?.call()
//
//    return response.status
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

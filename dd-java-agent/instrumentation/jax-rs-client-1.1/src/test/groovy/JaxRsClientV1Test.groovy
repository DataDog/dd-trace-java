import com.sun.jersey.api.client.Client
import com.sun.jersey.api.client.ClientResponse
import com.sun.jersey.api.client.filter.GZIPContentEncodingFilter
import com.sun.jersey.api.client.filter.LoggingFilter
import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.instrumentation.jaxrs.v1.JaxRsClientV1Decorator
import spock.lang.Shared
import spock.lang.Timeout

@Timeout(5)
class JaxRsClientV1Test extends HttpClientTest {

  @Shared
  Client client = Client.create()

  def setupSpec() {
    client.setConnectTimeout(CONNECT_TIMEOUT_MS)
    client.setReadTimeout(READ_TIMEOUT_MS)
    // Add filters to ensure spans aren't duplicated.
    client.addFilter(new LoggingFilter())
    client.addFilter(new GZIPContentEncodingFilter())
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, Closure callback) {
    def resource = client.resource(uri).requestBuilder
    headers.each { resource.header(it.key, it.value) }
    def body = BODY_METHODS.contains(method) ? "" : null
    ClientResponse response = resource.method(method, ClientResponse, body)
    callback?.call()

    return response.status
  }

  @Override
  String component() {
    return JaxRsClientV1Decorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    return "jax-rs.client.call"
  }

  boolean testCircularRedirects() {
    false
  }
}

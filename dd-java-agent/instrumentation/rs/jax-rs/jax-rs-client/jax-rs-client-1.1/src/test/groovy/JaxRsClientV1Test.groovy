import com.sun.jersey.api.client.Client
import com.sun.jersey.api.client.ClientResponse
import com.sun.jersey.api.client.filter.GZIPContentEncodingFilter
import com.sun.jersey.api.client.filter.LoggingFilter
import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.instrumentation.jaxrs.v1.JaxRsClientV1Decorator
import spock.lang.Shared
import spock.lang.Timeout

abstract class JaxRsClientV1Test extends HttpClientTest {

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
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def resource = client.resource(uri).requestBuilder
    headers.each { resource.header(it.key, it.value) }
    def reqBody = BODY_METHODS.contains(method) ? body : null
    ClientResponse response = resource.method(method, ClientResponse, reqBody)
    callback?.call()

    return response.status
  }

  @Override
  CharSequence component() {
    return JaxRsClientV1Decorator.DECORATE.component()
  }

  boolean testCircularRedirects() {
    false
  }
}

@Timeout(5)
class JaxRsClientV1NamingV0ForkedTest extends JaxRsClientV1Test {

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
    return "jax-rs.client.call"
  }
}

@Timeout(5)
class JaxRsClientV1NamingV1ForkedTest extends JaxRsClientV1Test implements TestingGenericHttpNamingConventions.ClientV1 {
}

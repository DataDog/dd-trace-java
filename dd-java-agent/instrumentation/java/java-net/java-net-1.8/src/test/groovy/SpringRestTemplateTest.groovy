import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.bootstrap.instrumentation.httpurlconnection.HttpUrlConnectionDecorator
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestTemplate
import spock.lang.Shared
import spock.lang.Timeout

@Timeout(5)
abstract class SpringRestTemplateTest extends HttpClientTest {

  @Shared
  ClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory()
  @Shared
  RestTemplate restTemplate = new RestTemplate(factory)

  def setupSpec() {
    factory.connectTimeout = CONNECT_TIMEOUT_MS
    factory.readTimeout = READ_TIMEOUT_MS
    factory.outputStreaming = false // https://stackoverflow.com/a/31649238
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def httpHeaders = new HttpHeaders()
    headers.each { httpHeaders.put(it.key, [it.value]) }
    def request = new HttpEntity<String>(httpHeaders)
    try {
      ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.resolve(method), request, String)
      callback?.call()
      return response.statusCode.value()
    } catch (HttpStatusCodeException ex) {
      ex.printStackTrace()
      return ex.statusCode.value()
    }
  }

  @Override
  CharSequence component() {
    return HttpUrlConnectionDecorator.DECORATE.component()
  }

  @Override
  boolean testCircularRedirects() {
    false
  }

  @Override
  boolean testConnectionFailure() {
    false
  }

  @Override
  boolean testRemoteConnection() {
    // FIXME: exception wrapped in ResourceAccessException
    return false
  }
}

class SpringRestTemplateV0ForkedTest extends SpringRestTemplateTest implements TestingGenericHttpNamingConventions.ClientV0 {
}

class SpringRestTemplateV1ForkedTest extends SpringRestTemplateTest implements TestingGenericHttpNamingConventions.ClientV1 {
}

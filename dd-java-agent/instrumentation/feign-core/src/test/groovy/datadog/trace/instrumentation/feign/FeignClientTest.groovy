package datadog.trace.instrumentation.feign

import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import feign.Client
import feign.Request
import feign.Response
import spock.lang.Timeout

import java.nio.charset.StandardCharsets

abstract class FeignClientTest extends HttpClientTest {

  def client = new Client.Default(null, null)

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    Map<String, Collection<String>> headerMap = new LinkedHashMap<>()
    headers.each { k, v ->
      headerMap.put(k, [v])
    }

    byte[] bodyBytes = body ? body.getBytes(StandardCharsets.UTF_8) : null

    def request = Request.create(
      Request.HttpMethod.valueOf(method),
      uri.toString(),
      headerMap,
      bodyBytes,
      StandardCharsets.UTF_8
      )

    def options = new Request.Options(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
    def response = client.execute(request, options)

    callback?.call()
    return response.status()
  }

  @Override
  CharSequence component() {
    return FeignDecorator.DECORATE.component()
  }

  @Override
  boolean testRedirects() {
    false
  }

  @Override
  boolean testProxy() {
    false
  }

  @Override
  boolean testCallbackWithParent() {
    false
  }
}

@Timeout(10)
class FeignClientV0ForkedTest extends FeignClientTest {
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
    return "http.request"
  }
}

@Timeout(10)
class FeignClientV1ForkedTest extends FeignClientTest implements TestingGenericHttpNamingConventions.ClientV1 {
}

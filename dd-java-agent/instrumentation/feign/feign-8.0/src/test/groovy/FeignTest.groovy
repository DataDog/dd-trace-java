import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.instrumentation.feign.FeignClientDecorator
import feign.Feign
import feign.Request
import feign.RequestLine
import feign.Util
import spock.lang.Shared

abstract class FeignTest extends HttpClientTest {

  @Shared
  def client

  def setupSpec() {
    client = Feign.builder()
      .target(TestInterface, "http://localhost:${server.address.port}")
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def request = Request.create(
      method,
      uri.toString(),
      headers.collectEntries { k, v -> [(k): [v]] },
      body ? body.bytes : null,
      Util.UTF_8
      )

    def options = new Request.Options()
    def feignClient = new feign.Client.Default(null, null)
    def response = feignClient.execute(request, options)

    callback?.call(response.body().asInputStream())
    return response.status()
  }

  @Override
  CharSequence component() {
    return FeignClientDecorator.DECORATE.component()
  }

  @Override
  boolean testRedirects() {
    false
  }

  @Override
  boolean testConnectionFailure() {
    false
  }

  @Override
  boolean testRemoteConnection() {
    // Feign doesn't work well with redirects in test harness
    return false
  }

  interface TestInterface {
    @RequestLine("GET /success")
    String success()
  }
}

class FeignV0ForkedTest extends FeignTest implements TestingGenericHttpNamingConventions.ClientV0 {
}

class FeignV1ForkedTest extends FeignTest implements TestingGenericHttpNamingConventions.ClientV1 {
}

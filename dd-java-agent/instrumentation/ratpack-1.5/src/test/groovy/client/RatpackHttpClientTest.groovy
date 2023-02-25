package client

import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingNettyHttpNamingConventions
import datadog.trace.instrumentation.netty41.client.NettyHttpClientDecorator
import ratpack.exec.ExecResult
import ratpack.http.client.HttpClient
import ratpack.test.exec.ExecHarness
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Timeout

import java.time.Duration

@Timeout(5)
class RatpackHttpClientTest extends HttpClientTest implements TestingNettyHttpNamingConventions.ClientV0 {

  @AutoCleanup
  @Shared
  ExecHarness exec = ExecHarness.harness()

  @Shared
  def client = HttpClient.of {
    it.readTimeout(Duration.ofSeconds(2))
    // Connect timeout added in 1.5
    try {
      // required for testing 1.9+
      it.execController(exec.controller)
    } catch (Exception ignore) {}
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    ExecResult<Integer> result = exec.yield {
      def resp = client.request(uri) { spec ->
        spec.connectTimeout(Duration.ofSeconds(2))
        spec.method(method)
        spec.headers { headersSpec ->
          headers.entrySet().each {
            headersSpec.add(it.key, it.value)
          }
        }
      }
      return resp.map {
        callback?.call()
        it.status.code
      }
    }
    return result.value
  }

  @Override
  CharSequence component() {
    return NettyHttpClientDecorator.DECORATE.component()
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
    return false
  }
}

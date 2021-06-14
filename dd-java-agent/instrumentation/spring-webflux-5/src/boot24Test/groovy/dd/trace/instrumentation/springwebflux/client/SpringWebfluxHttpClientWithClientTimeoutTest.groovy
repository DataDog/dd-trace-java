package dd.trace.instrumentation.springwebflux.client

import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.instrumentation.springwebflux.client.SpringWebfluxHttpClientDecorator
import org.springframework.http.HttpMethod
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient

import java.time.Duration

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

class SpringWebfluxHttpClientWithClientTimeoutTest extends HttpClientTest {

  def setupSpec() {
    try {
      createClient().get().uri("/success").exchange().block()
    } catch (Exception ignored) {
    }
    sleep(2000)
    TEST_WRITER.clear()
  }

  WebClient createClient() {
    HttpClient httpClient = HttpClient.create().responseTimeout(Duration.ofSeconds(1))
    return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient)).build()
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def hasParent = activeSpan() != null
    def client = createClient()
    ClientResponse response
    try {
      response = client.method(HttpMethod.resolve(method))
        .uri(uri)
        .headers { h -> headers.forEach({ key, value -> h.add(key, value) }) }
        .exchangeToMono({ res ->
          callback?.call()
        })
        .block()
    } catch (Exception ignored) {
      ignored.printStackTrace()
    }

    if (hasParent) {
      blockUntilChildSpansFinished(callback ? 3 : 2)
    }

    response.statusCode().value()
  }

  @Override
  CharSequence component() {
    return SpringWebfluxHttpClientDecorator.DECORATE.component()
  }

  @Override
  boolean testTimeout() {
    true
  }
}

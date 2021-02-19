package dd.trace.instrumentation.springwebflux.client

import org.springframework.http.HttpMethod
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import spock.lang.Timeout

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

@Timeout(5)
class SpringWebfluxHttpClientDoOnSuccessTest extends SpringWebfluxHttpClientBase {

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def hasParent = activeSpan() != null
    def client = createClient(component())
    ClientResponse response = client.method(HttpMethod.resolve(method))
      .uri(uri)
      .headers { h -> headers.forEach({ key, value -> h.add(key, value) }) }
      .exchange()
      .doOnSuccess { res ->
        callback?.call()
      }
      .block()

    if (hasParent) {
      blockUntilChildSpansFinished(callback ? 3 : 2)
    }

    check()

    response.statusCode().value()
  }

  @Override
  WebClient createClient(CharSequence component) {
    return WebClient.builder().build()
  }

  @Override
  void check() {
  }
}

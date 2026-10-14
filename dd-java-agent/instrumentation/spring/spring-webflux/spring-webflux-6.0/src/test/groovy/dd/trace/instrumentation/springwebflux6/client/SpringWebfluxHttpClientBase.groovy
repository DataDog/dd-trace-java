package dd.trace.instrumentation.springwebflux6.client

import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.springwebflux.client.SpringWebfluxHttpClientDecorator
import org.springframework.http.HttpMethod
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

abstract class SpringWebfluxHttpClientBase extends HttpClientTest implements TestingGenericHttpNamingConventions.ClientV0 {

  abstract WebClient createClient(CharSequence component)

  abstract void check()

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def hasParent = activeSpan() != null
    def client = createClient(component())
    ClientResponse response = client.method(HttpMethod.valueOf(method))
    .uri(uri)
    .headers {
      h -> headers.forEach({
        key, value -> h.add(key, value)
      })
    }
    .exchangeToMono (Mono::just)
    .doFinally {
      it -> callback?.call()
    }
    .block()

    if (hasParent) {
      blockUntilChildSpansFinished(callback ? 2 : 1)
    }

    check()

    response.statusCode().value()
  }

  @Override
  CharSequence component() {
    return SpringWebfluxHttpClientDecorator.DECORATE.component()
  }

  boolean testRedirects() {
    false
  }

  boolean testConnectionFailure() {
    false
  }

  boolean testRemoteConnection() {
    // FIXME: figure out how to configure timeouts.
    false
  }

  static class CollectingFilter implements ExchangeFilterFunction {
    volatile String collected = ""
    volatile int count = 0

    @Override
    Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
      def span = activeSpan()
      collected += span == null ? "null:" : String.valueOf(span.getTag(Tags.COMPONENT)) + ":"
      count += 1
      return next.exchange(request)
    }
  }
}

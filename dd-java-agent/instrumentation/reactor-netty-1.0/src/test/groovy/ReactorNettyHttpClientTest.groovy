import datadog.environment.JavaVirtualMachine
import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingNettyHttpNamingConventions
import datadog.trace.instrumentation.netty41.client.NettyHttpClientDecorator
import io.netty.handler.codec.http.HttpMethod
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.ByteBufFlux
import reactor.netty.ByteBufMono
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.HttpClientResponse
import spock.lang.IgnoreIf
import spock.lang.Shared

import java.time.Duration
import java.util.function.BiFunction

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

@IgnoreIf(reason = "TLS issues with OpenJ9", value = {
  JavaVirtualMachine.isJ9()
})
class ReactorNettyHttpClientTest extends HttpClientTest implements TestingNettyHttpNamingConventions.ClientV0 {

  @Shared
  HttpClient httpClient = HttpClient.create()
  .followRedirect(true)
  .responseTimeout(Duration.ofMillis(READ_TIMEOUT_MS))
  .disableRetry(true)

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    httpClient
      .headers({ hdrs ->
        headers.each {
          hdrs.set(it.key, it.value)
        }
      })
      .request(HttpMethod.valueOf(method))
      .uri(uri)
      .send(ByteBufFlux.fromString(Flux.just(body)))
      .responseSingle(new BiFunction<HttpClientResponse, ByteBufMono, Mono<Integer>>() {
        @Override
        Mono<Integer> apply(HttpClientResponse httpClientResponse, ByteBufMono byteBufMono) {
          Mono.just(httpClientResponse.status().code())
        }
      })
      .doOnSuccess({
        if (callback) {
          callback.run()
        }
      })
      .block()
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

  def "basic external GET request with parent"() {
    setup:
    def uri = URI.create("https://app.circleci.com/")
    when:
    def status = runUnderTrace("parent") {
      doRequest("GET", uri)
    }

    then:
    status == 200
    assertTraces(1) {
      trace(size(2)) {
        basicSpan(it, "parent")
        clientSpan(it, span(0), "GET", false, false, uri, 200, false, null, true)
      }
    }
  }
}

package dd.trace.instrumentation.springwebflux.client

import datadog.trace.agent.test.naming.TestingOtelHttpSemantics
import datadog.trace.instrumentation.netty41.client.HttpClientTracingHandler
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultHttpRequest
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import spock.lang.Timeout

@Timeout(5)
class SpringWebfluxHttpClientBasicTest extends SpringWebfluxHttpClientBase {

  @Override
  WebClient createClient(CharSequence component) {
    return WebClient.builder().build()
  }

  @Override
  void check() {
  }
}

@Timeout(5)
class SpringWebfluxHttpClientOtelSemanticsForkedTest extends SpringWebfluxHttpClientBasicTest implements TestingOtelHttpSemantics {
  def "an independent Netty request in a user filter retains its client span"() {
    setup:
    def independentRequest = {
      def channel = new EmbeddedChannel(new HttpClientTracingHandler())
      try {
        channel.writeOutbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, 'http://filter.example/token'))
        channel.readOutbound()
        channel.writeInbound(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK))
      } finally {
        channel.finishAndReleaseAll()
      }
    }
    def webClient = WebClient.builder().filter({ request, next ->
      def exchange = Mono.defer {
        if (beforeResponse) {
          independentRequest()
        }
        next.exchange(request).doOnNext {
          if (!beforeResponse) {
            independentRequest()
          }
        }
      }
      async ? exchange.subscribeOn(Schedulers.parallel()) : exchange
    } as ExchangeFilterFunction).build()

    when:
    def response = webClient.get().uri(server.address.resolve('/success')).exchange().block()
    consumeResponse(response, 'GET')
    TEST_WRITER.waitForTraces(1)

    then:
    def spans = TEST_WRITER.flatten()
    def webClientSpan = spans.find { it.getTag('component')?.toString() == 'spring-webflux-client' }
    def transportSpans = spans.findAll { it.getTag('component')?.toString() == 'netty-client' }
    transportSpans.size() == 1
    transportSpans[0].getTag('url.full').toString() == 'http://filter.example/token'
    transportSpans[0].parentId == webClientSpan.spanId

    where:
    beforeResponse | async
    true           | false
    true           | true
    false          | false
    false          | true
  }
}

class SpringWebfluxHttpClientFilterBuilderReuseOtelSemanticsForkedTest extends SpringWebfluxHttpClientFilterBuilderReuseTest implements TestingOtelHttpSemantics {
}

class SpringWebfluxHttpClientFiltersRemovedOtelSemanticsForkedTest extends SpringWebfluxHttpClientFiltersRemovedTest implements TestingOtelHttpSemantics {
}

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingNettyHttpNamingConventions
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.timeout.ReadTimeoutHandler
import reactor.core.publisher.Mono
import reactor.netty.ByteBufFlux
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.HttpClientResponse

import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class ReactorNettyTest extends HttpClientTest implements TestingNettyHttpNamingConventions.ClientV0 {
  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }

  @Override
  boolean testCircularRedirects() {
    false
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
    false
  }

  def "test timeout"() {
    when:
    runUnderTrace("parent") {
      doRequest("GET", server.address.resolve("/timeout"))
    }

    then:
    def ex = thrown(Exception)
    def thrownException = ex instanceof ExecutionException ? ex.cause : ex

    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent", null, thrownException)
        clientSpan(it, span(0), "GET", false, false, server.address.resolve("/timeout"), null, true, thrownException)
      }
    }
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    HttpClientResponse resp = HttpClient.create()
      .doAfterRequest({ r, c -> c.addHandler("TimeoutHandler", new ReadTimeoutHandler(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)) })
      .baseUrl(server.address.toString())
      .headers {
        headers.each { h ->
          it.set(h.key, h.value)
        }
      }
      .request(HttpMethod.valueOf(method))
      .uri(uri.toString())
      .send(ByteBufFlux.fromString(Mono.just(body)))
      .response()
      .doOnNext { callback?.call() }
      .block()
    return resp.status().code()
  }

  @Override
  CharSequence component() {
    return "netty-client"
  }
}

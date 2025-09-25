package client

import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingNettyHttpNamingConventions
import datadog.trace.instrumentation.netty41.client.NettyHttpClientDecorator
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpClientResponse
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.RequestOptions
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class VertxHttpClientForkedTest extends HttpClientTest implements TestingNettyHttpNamingConventions.ClientV0 {

  @Override
  boolean useStrictTraceWrites() {
    return false
  }

  @AutoCleanup
  @Shared
  def vertx = Vertx.vertx(new VertxOptions())

  @Shared
  def clientOptions = new HttpClientOptions()
  // vertx default is in seconds
  .setConnectTimeout(TimeUnit.SECONDS.toSeconds(3) as int)
  .setIdleTimeout(TimeUnit.SECONDS.toSeconds(5) as int)

  @AutoCleanup
  @Shared
  def httpClient = vertx.createHttpClient(clientOptions)

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    return doRequest(method, uri, headers, body, callback, -1)
  }

  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback, long timeout) {
    CompletableFuture<HttpClientResponse> future = new CompletableFuture<>()

    RequestOptions requestOptions = new RequestOptions()
      .setMethod(HttpMethod.valueOf(method))
      .setHost(uri.host)
      .setPort(uri.port)
      .setURI(uri.toString())
      .setTimeout(timeout)

    httpClient.request(requestOptions, { requestReadyToBeSend ->
      def request = requestReadyToBeSend.result()
      headers.each { request.putHeader(it.key, it.value) }
      request.send(body, { response ->
        try {
          callback?.call()
          future.complete(response.result())
        } catch (Exception e) {
          future.completeExceptionally(e)
        }
      })
    })

    HttpClientResponse response = future.get(10, TimeUnit.SECONDS)
    return response == null ? 0 : response.statusCode()
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

  boolean testRemoteConnection() {
    // FIXME: figure out how to configure timeouts.
    false
  }

  def "handle timeout"() {
    when:
    def status = doRequest(method, url, [:], "", null, timeout)

    then:
    status == 0
    assertTraces(1) {
      trace(size(1)) {
        clientSpan(it, null, method, false, false, url, null, true, null, false,
          ["error.stack": { String },
            "error.message": { String s -> s.startsWith("The timeout period of ${timeout}ms has been exceeded")},
            "error.type": { String s -> s.endsWith("NoStackTraceTimeoutException")}])
      }
    }

    where:
    timeout = 1000
    method = "GET"
    url = server.address.resolve("/timeout")
  }
}

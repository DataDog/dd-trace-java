package client

import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingNettyHttpNamingConventions
import datadog.trace.instrumentation.netty41.client.NettyHttpClientDecorator
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpClientResponse
import io.vertx.core.http.HttpMethod
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
  def clientOptions = new HttpClientOptions().setConnectTimeout(CONNECT_TIMEOUT_MS).setIdleTimeout(READ_TIMEOUT_MS)

  @AutoCleanup
  @Shared
  def httpClient = vertx.createHttpClient(clientOptions)

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    CompletableFuture<HttpClientResponse> future = new CompletableFuture<>()
    def request = httpClient.request(HttpMethod.valueOf(method), uri.port, uri.host, "$uri")
    headers.each { request.putHeader(it.key, it.value) }
    request.handler { response ->
      try {
        callback?.call()
        future.complete(response)
      } catch (Exception e) {
        future.completeExceptionally(e)
      }
    }
    request.end()

    return future.get(10, TimeUnit.SECONDS).statusCode()
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
}

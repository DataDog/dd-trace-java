package client

import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingNettyHttpNamingConventions
import datadog.trace.instrumentation.netty41.client.NettyHttpClientDecorator
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.client.HttpResponse
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
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
  def clientOptions = new WebClientOptions()
  // vertx default is in seconds
  .setConnectTimeout(TimeUnit.SECONDS.toSeconds(3) as int)
  .setIdleTimeout(TimeUnit.SECONDS.toSeconds(5) as int)

  @AutoCleanup
  @Shared
  def httpClient = WebClient.create(vertx, clientOptions)

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    return doRequest(method, uri, headers, body, callback, -1)
  }

  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback, long timeout) {
    CompletableFuture<HttpResponse> future = new CompletableFuture<>()

    def request = httpClient.request(HttpMethod.valueOf(method), uri.getPort(), uri.getHost(), uri.toString())
    headers.each { request.putHeader(it.key, it.value) }
    request.sendBuffer(Buffer.buffer(body)) { asyncResult ->
      if (asyncResult.succeeded()) {
        callback?.call()
        future.complete(asyncResult.result())
      } else {
        future.completeExceptionally(asyncResult.cause())
      }
    }

    def response = future.get(10, TimeUnit.SECONDS)
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
}

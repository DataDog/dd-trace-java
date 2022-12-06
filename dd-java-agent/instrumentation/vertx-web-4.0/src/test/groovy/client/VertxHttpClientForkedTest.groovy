package client

import datadog.trace.agent.test.base.HttpClientTest
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

class VertxHttpClientForkedTest extends HttpClientTest {

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
    CompletableFuture<HttpClientResponse> future = new CompletableFuture<>()

    httpClient.request(HttpMethod.valueOf(method), uri.port, uri.host, "$uri", { requestReadyToBeSend ->
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

    return future.get(10, TimeUnit.SECONDS).statusCode()
  }

  @Override
  CharSequence component() {
    return NettyHttpClientDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    return "netty.client.request"
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

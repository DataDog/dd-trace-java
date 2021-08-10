package client

import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import datadog.trace.instrumentation.netty41.client.NettyHttpClientDecorator
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpClientResponse
import io.vertx.core.http.HttpMethod
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Timeout

import java.util.concurrent.CompletableFuture

@Timeout(10)
class VertxHttpClientForkedTest extends HttpClientTest {

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

    return future.get().statusCode()
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

  @Override
  def setup() {
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.SUSPEND_RESUME,
      CheckpointValidationMode.THREAD_SEQUENCE)
  }
}

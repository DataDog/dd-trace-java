package client

import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingNettyHttpNamingConventions
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.instrumentation.netty41.client.NettyHttpClientDecorator
import io.vertx.core.VertxOptions
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.reactivex.core.Vertx
import io.vertx.reactivex.ext.web.client.WebClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Timeout

import java.util.concurrent.TimeUnit

@Timeout(10)
class VertxRxWebClientForkedTest extends HttpClientTest implements TestingNettyHttpNamingConventions.ClientV0 {

  @Override
  boolean useStrictTraceWrites() {
    return false
  }

  @AutoCleanup
  @Shared
  Vertx vertx = Vertx.vertx(new VertxOptions())

  @Shared
  def clientOptions = new WebClientOptions().setConnectTimeout(CONNECT_TIMEOUT_MS).setIdleTimeout(READ_TIMEOUT_MS)

  @AutoCleanup
  @Shared
  WebClient client = WebClient.create(vertx, clientOptions)

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def currentSpan = AgentTracer.activeSpan()

    def request = client.request(HttpMethod.valueOf(method), uri.port, uri.host, "$uri")
    headers.each { request.putHeader(it.key, it.value) }
    return request
      .rxSend()
      .doOnSuccess { response ->
        if (callback != null) {
          def scope = currentSpan != null ? AgentTracer.activateSpan(currentSpan) : AgentTracer.activateSpan(AgentTracer.noopSpan())
          try {
            callback.call()
          } finally {
            scope.close()
          }
        }
      }
      .map { it.statusCode() }
      .toObservable()
      .timeout(5, TimeUnit.SECONDS)
      .blockingFirst()
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

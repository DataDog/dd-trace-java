package client

import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.instrumentation.netty41.client.NettyHttpClientDecorator
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpClientResponse
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.RequestOptions
import io.vertx.core.http.impl.HttpClientImpl
import io.vertx.core.net.ProxyOptions
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Timeout

import java.util.concurrent.CompletableFuture

@Timeout(10)
class VertxHttpClientTest extends HttpClientTest {

  @Override
  boolean useStrictTraceWrites() {
    return false
  }

  @AutoCleanup
  @Shared
  def vertx = Vertx.vertx(new VertxOptions())
  @Shared
  def clientOptions = new HttpClientOptions()
  .setConnectTimeout(CONNECT_TIMEOUT_MS)
  .setIdleTimeout(READ_TIMEOUT_MS)
  .setVerifyHost(false)
  .setSsl(true)
  @Shared
  HttpClientImpl client = vertx.createHttpClient(clientOptions)
  @Shared
  HttpClientImpl proxiedClient

  def setupSpec() {
    def sslContext = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build()
    client.sslHelper.sslContext = sslContext
    def proxyOpts = new ProxyOptions()
    proxyOpts.setHost("localhost")
    proxyOpts.setPort(proxy.port)
    proxiedClient = vertx.createHttpClient(clientOptions.setProxyOptions(proxyOpts))
    proxiedClient.sslHelper.sslContext = sslContext
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    CompletableFuture<HttpClientResponse> future = new CompletableFuture<>()
    def isProxy = uri.fragment != null && uri.fragment.equals("proxy")
    def client = isProxy ? proxiedClient : client
    def request = client.request(HttpMethod.valueOf(method), new RequestOptions()
      .setSsl(uri.scheme.equals("https"))
      .setHost(uri.host)
      .setPort(uri.port)
      .setURI("$uri"))
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
  boolean testProxy() {
    // inconsistent span results due to keep-alive
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

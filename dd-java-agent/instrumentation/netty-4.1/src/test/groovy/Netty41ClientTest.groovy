import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.netty41.client.NettyHttpClientDecorator
import io.netty.channel.AbstractChannel
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import org.asynchttpclient.AsyncCompletionHandler
import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.BoundRequestBuilder
import org.asynchttpclient.DefaultAsyncHttpClientConfig
import org.asynchttpclient.Response
import org.asynchttpclient.proxy.ProxyServer
import spock.lang.AutoCleanup
import spock.lang.Retry
import spock.lang.Timeout

import java.util.concurrent.ExecutionException

import static datadog.trace.agent.test.utils.PortUtils.UNUSABLE_PORT
import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static org.asynchttpclient.Dsl.asyncHttpClient

@Retry
@Timeout(5)
class Netty41ClientTest extends HttpClientTest {

  @Override
  boolean useStrictTraceWrites() {
    // NettyPromiseInstrumentation results in unfinished continuations.
    return false
  }

  def clientConfig = DefaultAsyncHttpClientConfig.Builder.newInstance()
    .setConnectTimeout(CONNECT_TIMEOUT_MS)
    .setRequestTimeout(READ_TIMEOUT_MS)
    .setReadTimeout(READ_TIMEOUT_MS)
    .setSslContext(SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build())
    .setMaxRequestRetry(0)

  // Can't be @Shared otherwise field-injected classes get loaded too early.
  @AutoCleanup
  AsyncHttpClient asyncHttpClient = asyncHttpClient(clientConfig)

  // Can't be @Shared otherwise field-injected classes get loaded too early.
  @AutoCleanup
  AsyncHttpClient proxiedAsyncHttpClient = asyncHttpClient(clientConfig
    .setProxyServer(new ProxyServer.Builder("localhost", proxy.port).build()))

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers = [:], String body = "", Closure callback = null) {
    def proxy = uri.fragment != null && uri.fragment.equals("proxy")
    def client = proxy ? proxiedAsyncHttpClient : asyncHttpClient
    def methodName = "prepare" + method.toLowerCase().capitalize()
    BoundRequestBuilder requestBuilder = client."$methodName"(uri.toString())
    headers.each { requestBuilder.setHeader(it.key, it.value) }
    requestBuilder.setBody(body)
    def response = requestBuilder.execute(new AsyncCompletionHandler() {
      @Override
      Object onCompleted(Response response) throws Exception {
        callback?.call()
        return response
      }
    }).get()
    blockUntilChildSpansFinished(proxy ? 2 : 1)
    return response.statusCode
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
    // with followRedirect=true, the client generates an extra request span
    false
  }

  @Override
  boolean testConnectionFailure() {
    false
  }

  @Override
  boolean testSecure() {
    true
  }

  @Override
  boolean testProxy() {
    true
  }

  @Override
  boolean testRemoteConnection() {
    return false
  }

  def "connection error (unopened port)"() {
    given:
    def uri = new URI("http://127.0.0.1:$UNUSABLE_PORT/")

    when:
    runUnderTrace("parent") {
      doRequest(method, uri)
    }

    then:
    def ex = thrown(Exception)
    ex.cause instanceof ConnectException
    def thrownException = ex instanceof ExecutionException ? ex.cause : ex

    and:
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent", null, thrownException)

        span {
          operationName "netty.connect"
          resourceName "netty.connect"
          childOf span(0)
          errored true
          tags {
            "$Tags.COMPONENT" "netty"
            errorTags AbstractChannel.AnnotatedConnectException, "Connection refused: /127.0.0.1:$UNUSABLE_PORT"
            defaultTags()
          }
        }
      }
    }

    where:
    method = "GET"
  }
}

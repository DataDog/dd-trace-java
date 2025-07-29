import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.instrumentation.apachehttpasyncclient.ApacheHttpAsyncClientDecorator
import org.apache.http.HttpHost
import org.apache.http.HttpResponse
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.utils.URIBuilder
import org.apache.http.concurrent.FutureCallback
import org.apache.http.impl.nio.client.HttpAsyncClients
import org.apache.http.message.BasicHeader
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Timeout

import java.util.concurrent.CountDownLatch

@Timeout(5)
abstract class ApacheHttpAsyncClientTest extends HttpClientTest {

  @Shared
  RequestConfig requestConfig = RequestConfig.custom()
  .setConnectTimeout(CONNECT_TIMEOUT_MS)
  .setSocketTimeout(READ_TIMEOUT_MS)
  .build()

  @AutoCleanup
  @Shared
  def client = HttpAsyncClients.custom().setDefaultRequestConfig(requestConfig).build()

  def setupSpec() {
    client.start()
  }

  protected HttpUriRequest createRequest(String method, URI uri) {
    new HttpUriRequest(method, uri)
  }

  protected HttpResponse executeRequest(HttpUriRequest request, URI uri, FutureCallback<HttpResponse> handler) {
    client.execute(request, handler).get()
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def request = createRequest(method, uri)
    headers.entrySet().each {
      request.addHeader(new BasicHeader(it.key, it.value))
    }

    def latch = new CountDownLatch(callback == null ? 0 : 1)

    def handler = callback == null ? null : new FutureCallback<HttpResponse>() {

        @Override
        void completed(HttpResponse result) {
          callback()
          latch.countDown()
        }

        @Override
        void failed(Exception ex) {
          latch.countDown()
        }

        @Override
        void cancelled() {
          latch.countDown()
        }
      }

    try {
      def response = executeRequest(request, uri, handler)
      response.entity?.content?.close() // Make sure the connection is closed.
      latch.await()
      response.statusLine.statusCode
    } finally {
      blockUntilChildSpansFinished(1)
    }
  }

  @Override
  CharSequence component() {
    return ApacheHttpAsyncClientDecorator.DECORATE.component()
  }

  @Override
  Integer statusOnRedirectError() {
    return 302
  }

  @Override
  boolean testRemoteConnection() {
    false // otherwise SocketTimeoutException for https requests
  }
}

class ApacheHttpAsyncClientV0Test extends ApacheHttpAsyncClientTest implements TestingGenericHttpNamingConventions.ClientV0 {
}

class ApacheHttpAsyncClientV1ForkedTest extends ApacheHttpAsyncClientTest implements TestingGenericHttpNamingConventions.ClientV1 {
}

class ApacheHttpAsyncClientHostRequestTest extends ApacheHttpAsyncClientV0Test {

  def relativizeUri(URI uri) {
    new URIBuilder(uri).setHost(null).setPort(-1).setScheme(null).build()
  }

  @Override
  protected HttpUriRequest createRequest(String method, URI uri) {
    new HttpUriRequest(method, relativizeUri(uri))
  }

  @Override
  protected HttpResponse executeRequest(HttpUriRequest request, URI uri, FutureCallback<HttpResponse> handler) {
    client.execute(new HttpHost(uri.getHost(), uri.getPort()), request, handler).get()
  }
}

class ApacheHttpAsyncClientHostRequestLegacyForkedTest extends ApacheHttpAsyncClientHostRequestTest {
  @Override
  void setup() {
    injectSysConfig("httpasyncclient4.legacy.tracing.enabled", "true")
  }

  @Override
  void clientSpan(
    TraceAssert trace,
    Object parentSpan,
    String method,
    boolean renameService,
    boolean tagQueryString,
    URI uri,
    Integer status,
    boolean error,
    Throwable exception,
    boolean ignorePeer,
    Map<String, Serializable> extraTags) {
    super.clientSpan(trace, parentSpan, method, false,  // spit-by-host is also buggy since host info is missing
      tagQueryString, relativizeUri(uri), status, error, exception, true, extraTags)
  }
}

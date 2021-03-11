import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.instrumentation.apachehttpasyncclient.ApacheHttpAsyncClientDecorator
import org.apache.http.HttpHost
import org.apache.http.HttpResponse
import org.apache.http.client.config.RequestConfig
import org.apache.http.concurrent.FutureCallback
import org.apache.http.impl.nio.client.HttpAsyncClients
import org.apache.http.message.BasicHeader
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Timeout

import java.util.concurrent.CountDownLatch

@Timeout(5)
class ApacheHttpAsyncClientTest extends HttpClientTest {

  @Shared
  def requestConfig = RequestConfig.custom()
  .setConnectTimeout(CONNECT_TIMEOUT_MS)
  .setSocketTimeout(READ_TIMEOUT_MS)

  @AutoCleanup
  @Shared
  def client = HttpAsyncClients.custom()
  .setDefaultRequestConfig(requestConfig.build())
  .setSSLContext(server.sslContext)
  .build()

  @AutoCleanup
  @Shared
  def proxiedClient = HttpAsyncClients.custom()
  .setDefaultRequestConfig(requestConfig.setProxy(new HttpHost("localhost", proxy.port)).build())
  .setSSLContext(server.sslContext)
  .build()

  def setupSpec() {
    client.start()
    proxiedClient.start()
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def isProxy = uri.fragment != null && uri.fragment.equals("proxy")
    def request = new HttpUriRequest(method, uri)
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
      def response = (isProxy ? proxiedClient : client).execute(request, handler).get()
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

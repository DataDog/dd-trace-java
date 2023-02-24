import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.instrumentation.apachehttpasyncclient.ApacheHttpAsyncClientDecorator
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

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
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
      def response = client.execute(request, handler).get()
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

class ApacheHttpAsyncClientV0ForkedTest extends ApacheHttpAsyncClientTest implements TestingGenericHttpNamingConventions.ClientV0 {
}

class ApacheHttpAsyncClientV1ForkedTest extends ApacheHttpAsyncClientTest implements TestingGenericHttpNamingConventions.ClientV1 {
}

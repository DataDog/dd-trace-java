import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.instrumentation.apachehttpclient5.ApacheHttpClientDecorator
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager
import org.apache.hc.core5.http.ClassicHttpResponse
import org.apache.hc.core5.http.io.HttpClientResponseHandler
import org.apache.hc.core5.http.message.BasicClassicHttpRequest
import org.apache.hc.core5.http.message.BasicHeader
import spock.lang.Shared
import spock.lang.Timeout

import java.util.concurrent.TimeUnit

@Timeout(5)
class ApacheHttpClientResponseHandlerTest extends HttpClientTest implements TestingGenericHttpNamingConventions.ClientV0 {

  @Shared
  def client = HttpClients.custom()
  .setConnectionManager(new BasicHttpClientConnectionManager())
  .setDefaultRequestConfig(RequestConfig.custom()
  .setConnectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  .build()).build()

  @Shared
  def handler = new HttpClientResponseHandler<Integer>() {
    @Override
    Integer handleResponse(ClassicHttpResponse response) {
      try {
        return response.code
      }
      finally {
        response.close()
      }
    }
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def request = new BasicClassicHttpRequest(method, uri)
    headers.entrySet().each {
      request.addHeader(new BasicHeader(it.key, it.value))
    }

    CloseableHttpResponse response = null
    def status = client.execute(request, handler)

    // handler execution is included within the client span, so we can't call the callback there.
    callback?.call()

    return status
  }

  @Override
  CharSequence component() {
    return ApacheHttpClientDecorator.DECORATE.component()
  }
}

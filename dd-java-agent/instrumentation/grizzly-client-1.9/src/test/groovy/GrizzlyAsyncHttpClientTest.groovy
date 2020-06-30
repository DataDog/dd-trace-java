

import com.ning.http.client.AsyncCompletionHandler
import com.ning.http.client.AsyncHttpClient
import com.ning.http.client.Request
import com.ning.http.client.RequestBuilder
import com.ning.http.client.Response
import com.ning.http.client.uri.Uri
import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.instrumentation.grizzly.client.ClientDecorator
import spock.lang.AutoCleanup
import spock.lang.Shared

class GrizzlyAsyncHttpClientTest extends HttpClientTest {

  static {
    System.setProperty("dd.integration.grizzly-client.enabled", "true")
  }

  @AutoCleanup
  @Shared
  def client = new AsyncHttpClient()

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, Closure callback) {

    RequestBuilder requestBuilder = new RequestBuilder(method)
      .setUri(Uri.create(uri.toString()))
    headers.entrySet().each {
      requestBuilder.addHeader(it.key, it.value)
    }
    Request request = requestBuilder.build()

    def handler = new AsyncCompletionHandlerMock(callback)

    def response = client.executeRequest(request, handler).get()
    response.statusCode
  }

  @Override
  String component() {
    return ClientDecorator.DECORATE.component()
  }

  class AsyncCompletionHandlerMock extends AsyncCompletionHandler<Response> {

    private Closure callback

    AsyncCompletionHandlerMock(Closure callback) {
      this.callback = callback
    }

    @Override
    Response onCompleted(Response response) throws Exception {
      if (callback != null) {
        callback()
      }
      return response
    }
  }

  @Override
  boolean testRedirects() {
    false
  }

  @Override
  boolean testConnectionFailure() {
    false
  }

  @Override
  boolean testRemoteConnection() {
    return false
  }
}



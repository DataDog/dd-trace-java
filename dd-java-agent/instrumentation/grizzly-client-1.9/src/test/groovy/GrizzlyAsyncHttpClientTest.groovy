import com.ning.http.client.AsyncCompletionHandler
import com.ning.http.client.AsyncHttpClient
import com.ning.http.client.Request
import com.ning.http.client.RequestBuilder
import com.ning.http.client.Response
import com.ning.http.client.uri.Uri
import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.instrumentation.grizzly.client.ClientDecorator
import spock.lang.AutoCleanup
import spock.lang.Shared

abstract class GrizzlyAsyncHttpClientTest extends HttpClientTest {

  @AutoCleanup
  @Shared
  def client = new AsyncHttpClient()

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.integration.grizzly-client.enabled", "true")
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {

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
  CharSequence component() {
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

class GrizzlyAsyncHttpClientV0ForkedTest extends GrizzlyAsyncHttpClientTest implements TestingGenericHttpNamingConventions.ClientV0 {
}

class GrizzlyAsyncHttpClientV1ForkedTest extends GrizzlyAsyncHttpClientTest implements TestingGenericHttpNamingConventions.ClientV1 {
}


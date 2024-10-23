import com.ning.http.client.AsyncHandler
import com.ning.http.client.AsyncHttpClient
import com.ning.http.client.HttpResponseBodyPart
import com.ning.http.client.HttpResponseHeaders
import com.ning.http.client.HttpResponseStatus
import com.ning.http.client.Request
import com.ning.http.client.RequestBuilder
import com.ning.http.client.Response
import com.ning.http.client.uri.Uri
import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.instrumentation.grizzly.client.ClientDecorator
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.util.concurrent.Executors

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

    def handler = new AsyncHandlerMock(callback)

    def response = client.executeRequest(request, handler).get()
    response.statusCode
  }

  @Override
  CharSequence component() {
    return ClientDecorator.DECORATE.component()
  }

  class AsyncHandlerMock implements AsyncHandler<Response> {
    private final Closure callback
    private final Response.ResponseBuilder builder = new Response.ResponseBuilder()

    AsyncHandlerMock(Closure callback) {
      this.callback = callback
    }

    @Override
    STATE onBodyPartReceived(HttpResponseBodyPart content) throws Exception {
      this.builder.accumulate(content)
      return STATE.CONTINUE
    }

    @Override
    STATE onStatusReceived(HttpResponseStatus status) throws Exception {
      this.builder.reset()
      this.builder.accumulate(status)
      return STATE.CONTINUE
    }

    @Override
    STATE onHeadersReceived(HttpResponseHeaders headers) throws Exception {
      this.builder.accumulate(headers)
      return STATE.CONTINUE
    }

    @Override
    Response onCompleted() throws Exception {
      if (callback != null) {
        Executors.newSingleThreadExecutor().submit (callback)
      }
      builder.build()
    }

    void onThrowable(Throwable t) {
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

class GrizzlyAsyncHttpClientV0Test extends GrizzlyAsyncHttpClientTest implements TestingGenericHttpNamingConventions.ClientV0 {
}

class GrizzlyAsyncHttpClientV1ForkedTest extends GrizzlyAsyncHttpClientTest implements TestingGenericHttpNamingConventions.ClientV1 {
}


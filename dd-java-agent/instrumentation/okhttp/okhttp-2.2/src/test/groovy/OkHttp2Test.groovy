import com.squareup.okhttp.*
import com.squareup.okhttp.internal.http.HttpMethod
import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.instrumentation.okhttp2.OkHttpClientDecorator
import spock.lang.Shared
import spock.lang.Timeout

import java.util.concurrent.TimeUnit

abstract class OkHttp2Test extends HttpClientTest {
  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }

  @Shared
  def client = new OkHttpClient()

  def setupSpec() {
    client.setConnectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    client.setReadTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    client.setWriteTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    final contentType = headers.remove("Content-Type")
    def reqBody = HttpMethod.requiresRequestBody(method) ? RequestBody.create(MediaType.parse(contentType ?: "text/plain"), body) : null

    def request = new Request.Builder()
      .url(uri.toURL())
      .method(method, reqBody)
      .headers(Headers.of(HeadersUtil.headersToArray(headers)))
      .build()
    def response = client.newCall(request).execute()
    callback?.call(response.body().byteStream())
    return response.code()
  }


  @Override
  CharSequence component() {
    return OkHttpClientDecorator.DECORATE.component()
  }

  boolean testRedirects() {
    false
  }

  @Override
  boolean testAppSecClientRequest() {
    true
  }

  @Override
  boolean testAppSecClientRedirect() {
    true
  }
}

@Timeout(5)
class OkHttp2V0ForkedTest extends OkHttp2Test {

  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return null
  }

  @Override
  String operation() {
    return "okhttp.request"
  }
}

@Timeout(5)
class OkHttp2V1ForkedTest extends OkHttp2Test implements TestingGenericHttpNamingConventions.ClientV1 {
}

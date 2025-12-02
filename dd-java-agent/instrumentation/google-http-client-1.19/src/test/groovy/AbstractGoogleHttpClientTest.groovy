import com.google.api.client.http.GenericUrl
import com.google.api.client.http.HttpRequest
import com.google.api.client.http.HttpResponse
import com.google.api.client.http.javanet.NetHttpTransport
import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.instrumentation.googlehttpclient.GoogleHttpClientDecorator
import spock.lang.Shared

abstract class AbstractGoogleHttpClientTest extends HttpClientTest {

  @Shared
  def requestFactory = new NetHttpTransport().createRequestFactory()

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    doRequest(method, uri, headers, callback, false)
  }

  int doRequest(String method, URI uri, Map<String, String> headers, Closure callback, boolean throwExceptionOnError) {
    GenericUrl genericUrl = new GenericUrl(uri)

    HttpRequest request = requestFactory.buildRequest(method, genericUrl, null)
    request.connectTimeout = CONNECT_TIMEOUT_MS
    request.readTimeout = READ_TIMEOUT_MS

    // GenericData::putAll method converts all known http headers to List<String>
    // and lowercase all other headers
    def ci = request.getHeaders().getClassInfo()
    request.getHeaders().putAll(headers.collectEntries { name, value ->
      [(name): (ci.getFieldInfo(name) != null ? [value]: value.toLowerCase())]
    })

    request.setThrowExceptionOnExecuteError(throwExceptionOnError)

    HttpResponse response = executeRequest(request)
    callback?.call()

    return response.getStatusCode()
  }

  abstract HttpResponse executeRequest(HttpRequest request)

  @Override
  String component() {
    return GoogleHttpClientDecorator.DECORATE.component()
  }

  @Override
  boolean testCircularRedirects() {
    // Circular redirects don't throw an exception with Google Http Client
    return false
  }
}

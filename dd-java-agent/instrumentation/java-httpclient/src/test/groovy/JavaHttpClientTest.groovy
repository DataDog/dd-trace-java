import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.instrumentation.javahttpclient.JavaHttpClientDecorator
import spock.lang.Shared
import spock.lang.Timeout

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

abstract class JavaHttpClientTest<T extends HttpRequest> extends HttpClientTest {

  @Shared
  def client = HttpClient.newBuilder()
    .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
    .build()

  @Override
  CharSequence component() {
    return JavaHttpClientDecorator.DECORATE.component()
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def request = createRequest(method, uri, headers)

    HttpResponse<?> response = executeRequest(request, uri)
    callback?.call()
    return response.statusCode()
  }

  abstract T createRequest(String method, URI uri, Map<String, String> headers)

  abstract HttpResponse<?> executeRequest(T request, URI uri)

  static String fullPathFromURI(URI uri) {
    StringBuilder builder = new StringBuilder()
    if (uri.getPath() != null) {
      builder.append(uri.getPath())
    }

    if (uri.getQuery() != null) {
      builder.append('?')
      builder.append(uri.getQuery())
    }

    if (uri.getFragment() != null) {
      builder.append('#')
      builder.append(uri.getFragment())
    }
    return builder.toString()
  }
}

@Timeout(5)
class JavaHttpClientHostRequest extends JavaHttpClientTest<HttpRequest> {
  @Override
  HttpRequest createRequest(String method, URI uri, Map<String, String> headers) {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
      .uri(uri)
      .method(method, HttpRequest.BodyPublishers.noBody())
    for (Map.Entry<String, String> entry : headers) {
      builder = builder.setHeader(entry.key, entry.getValue())
    }

    return builder.build()
  }

  @Override
  HttpResponse<?> executeRequest(HttpRequest request, URI uri) {
    return client.send(request, HttpResponse.BodyHandlers.discarding())
  }

  @Override
  boolean testRemoteConnection() {
    return false
  }
}

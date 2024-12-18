package datadog.trace.instrumentation.httpclient

import java.net.http.HttpRequest
import java.net.http.HttpResponse

class JavaHttpClientAsyncTest extends JavaHttpClientTest {
  @Override
  int doRequest(String method, URI uri, List<List<String>> headers, String body, Closure callback) {
    def request = HttpRequest.newBuilder()
      .uri(uri)
      .method(method, HttpRequest.BodyPublishers.ofString(body))
    if (headers != null) {
      headers.each { request.header(it[0], it[1]) }
    }

    def response = client.sendAsync(request.build(), HttpResponse.BodyHandlers.discarding())
    def ret = response
      .thenApply({ r -> r.statusCode() })
      .join()
    callback?.call()
    return ret
  }

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
    return "http.request"
  }
}

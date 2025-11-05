package datadog.trace.instrumentation.httpclient

import datadog.trace.agent.test.base.HttpClientTest
import static datadog.trace.instrumentation.httpclient.JavaNetClientDecorator.DECORATE

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

abstract class JavaHttpClientTest extends HttpClientTest {
  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }

  def client = HttpClient.newBuilder()
  .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
  .build()

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def request = HttpRequest.newBuilder()
      .uri(uri)
      .method(method, HttpRequest.BodyPublishers.ofString(body))
    if (headers != null) {
      headers.each { key, value -> request.header(key, value) }
    }

    def response = client.send(request.build(), HttpResponse.BodyHandlers.discarding())
    callback?.call()
    return response.statusCode()
  }


  @Override
  CharSequence component() {
    return DECORATE.component()
  }

  boolean testRedirects() {
    false
  }
}

class JavaHttpClientV0Test extends JavaHttpClientTest {

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

class JavaHttpClientV1ForkedTest extends JavaHttpClientTest {

  @Override
  int version() {
    return 1
  }

  @Override
  String service() {
    return null
  }

  @Override
  String operation() {
    return "http.client.request"
  }
}

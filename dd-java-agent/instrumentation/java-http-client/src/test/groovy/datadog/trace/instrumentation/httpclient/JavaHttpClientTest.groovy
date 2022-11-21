package datadog.trace.instrumentation.httpclient

import datadog.trace.agent.test.base.HttpClientTest
import static datadog.trace.instrumentation.httpclient.JavaNetClientDecorator.DECORATE

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class JavaHttpClientTest extends HttpClientTest {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    // disable tracer metrics because it uses OkHttp and class loading is
    // not isolated in tests
    injectSysConfig("dd.trace.tracer.metrics.enabled", "false")
  }

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

  @Override
  String expectedOperationName() {
    return "http.request"
  }


  boolean testRedirects() {
    false
  }
}

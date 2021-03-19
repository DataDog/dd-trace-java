import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.instrumentation.okhttp3.OkHttpClientDecorator
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.internal.http.HttpMethod
import spock.lang.Timeout

import java.util.concurrent.TimeUnit

@Timeout(5)
class OkHttp3Test extends HttpClientTest {

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

  def client = new OkHttpClient.Builder()
  .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  .writeTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  .build()

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def reqBody = HttpMethod.requiresRequestBody(method) ? RequestBody.create(MediaType.parse("text/plain"), body) : null
    def request = new Request.Builder()
      .url(uri.toURL())
      .method(method, reqBody)
      .headers(Headers.of(headers)).build()
    def response = client.newCall(request).execute()
    callback?.call()
    return response.code()
  }


  @Override
  CharSequence component() {
    return OkHttpClientDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    return "okhttp.request"
  }


  boolean testRedirects() {
    false
  }

  def "request to agent not traced"() {
    when:
    def status = doRequest(method, url, ["Datadog-Meta-Lang": "java"])

    then:
    status == 200
    assertTraces(1) {
      server.distributedRequestTrace(it)
    }

    where:
    path                                | tagQueryString
    "/success"                          | false
    "/success"                          | true
    "/success?with=params"              | false
    "/success?with=params"              | true
    "/success#with+fragment"            | true
    "/success?with=params#and=fragment" | true

    method = "GET"
    url = server.address.resolve(path)
  }
}

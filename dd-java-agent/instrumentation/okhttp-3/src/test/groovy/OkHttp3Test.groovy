import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.instrumentation.okhttp3.OkHttpClientDecorator
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.internal.http.HttpMethod

class OkHttp3Test extends HttpClientTest<OkHttpClientDecorator> {

  def client = new OkHttpClient()

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, Closure callback) {
    def body = HttpMethod.requiresRequestBody(method) ? RequestBody.create(MediaType.parse("text/plain"), "") : null
    def request = new Request.Builder()
      .url(uri.toURL())
      .method(method, body)
      .headers(Headers.of(headers)).build()
    def response = client.newCall(request).execute()
    callback?.call()
    return response.code()
  }


  @Override
  OkHttpClientDecorator decorator() {
    return OkHttpClientDecorator.DECORATE
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
      server.distributedRequestTrace(it, 0)
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

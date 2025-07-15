import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.instrumentation.okhttp3.OkHttpClientDecorator
import okhttp3.*
import okhttp3.internal.http.HttpMethod
import spock.lang.Timeout

import java.util.concurrent.TimeUnit

abstract class OkHttp3Test extends HttpClientTest {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    // disable tracer metrics because it uses OkHttp and class loading is
    // not isolated in tests
    injectSysConfig("dd.trace.tracer.metrics.enabled", "false")
  }

  @Override
  boolean isTestAgentEnabled() {
    return false
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

  def "baggage span tags are properly added"() {
    setup:
    // W3C Baggage header format: key1=value1,key2=value2,key3=value3
    def baggageHeader = "user.id=bark,session.id=test-sess1,account.id=fetch,language=en"
    def sentHeaders = [:]

    when:
    def status
    // Capture the headers that OkHttp3 sends
    def interceptor = new Interceptor() {
        @Override
        Response intercept(Chain chain) throws IOException {
          def request = chain.request()
          // Capture all headers sent
          request.headers().names().each { headerName ->
            sentHeaders[headerName] = request.header(headerName)
          }
          return chain.proceed(request)
        }
      }

    def testClient = new OkHttpClient.Builder()
      .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      .writeTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      .addInterceptor(interceptor)
      .build()

    def request = new Request.Builder()
      .url(server.address.resolve("/success").toURL())
      .header("baggage", baggageHeader)  // Pass baggage directly in header
      .get()
      .build()
    def response = testClient.newCall(request).execute()
    status = response.code()

    then:
    status == 200
    // Verify baggage header was sent
    sentHeaders["baggage"] == baggageHeader
    sentHeaders["baggage"].contains("user.id=bark")
    sentHeaders["baggage"].contains("session.id=test-sess1")
    sentHeaders["baggage"].contains("account.id=fetch")
    sentHeaders["baggage"].contains("language=en")

    // Verify the resulting span has the correct baggage tags (only default configured keys)
    assertTraces(1) {
      trace(1) {
        clientSpan(it, null, "GET", false, false, server.address.resolve("/success"), 200, false, null, false, [
          // Should have baggage tags for keys in default configuration
          "baggage.user.id": "bark",
          "baggage.session.id": "test-sess1",
          "baggage.account.id": "fetch",
          // "baggage.language" should NOT be present since it's not in default config
        ])
      }
    }
  }
}

@Timeout(5)
class OkHttp3V0ForkedTest extends OkHttp3Test {

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
class OkHttp3V1ForkedTest extends OkHttp3Test implements TestingGenericHttpNamingConventions.ClientV1 {
}

import com.squareup.okhttp.*
import com.squareup.okhttp.internal.http.HttpMethod
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.bootstrap.instrumentation.api.AgentTracer

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan
import static java.util.concurrent.TimeUnit.SECONDS

abstract class OkHttp2AsyncTest extends OkHttp2Test {
  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
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

    AtomicReference<Response> responseRef = new AtomicReference()
    AtomicReference<Exception> exRef = new AtomicReference()
    def latch = new CountDownLatch(1)

    client.newCall(request).enqueue(new Callback() {
        void onResponse(Response response) {
          responseRef.set(response)
          callback?.call(response.body().byteStream())
          latch.countDown()
        }

        void onFailure(Request req, IOException e) {
          exRef.set(e)
          callback?.call(e)
          latch.countDown()
        }
      })
    latch.await(10, SECONDS)
    if (exRef.get() != null) {
      throw exRef.get()
    }
    return responseRef.get().code()
  }

  def "callbacks should carry context with error = #error" () {

    when:
    def captured = noopSpan()
    try {
      TraceUtils.runUnderTrace("parent", {
        doRequest(method, url, ["Datadog-Meta-Lang": "java"], "", { captured = AgentTracer.activeSpan() })
      })
    } catch (Exception e) {
      assert error == true
    }

    then:
    "parent".contentEquals(captured.getOperationName())

    where:
    url                                 | error
    server.address.resolve("/success")  | false
    new URI("http://240.0.0.1")         | true

    method = "GET"
  }
}

class OkHttp2AsyncV0ForkedTest extends OkHttp2AsyncTest {

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

class OkHttp2AsyncV1ForkedTest extends OkHttp2AsyncTest implements TestingGenericHttpNamingConventions.ClientV1 {
}

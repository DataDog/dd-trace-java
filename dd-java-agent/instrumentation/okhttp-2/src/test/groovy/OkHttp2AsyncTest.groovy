import com.squareup.okhttp.Callback
import com.squareup.okhttp.Headers
import com.squareup.okhttp.MediaType
import com.squareup.okhttp.Request
import com.squareup.okhttp.RequestBody
import com.squareup.okhttp.Response
import com.squareup.okhttp.internal.http.HttpMethod

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

import static java.util.concurrent.TimeUnit.SECONDS

class OkHttp2AsyncTest extends OkHttp2Test {
  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def isProxy = uri.fragment != null && uri.fragment.equals("proxy")
    client.setProxy(isProxy ? proxy.proxyConfig : Proxy.NO_PROXY)

    def reqBody = HttpMethod.requiresRequestBody(method) ? RequestBody.create(MediaType.parse("text/plain"), body) : null
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
          callback?.call()
          latch.countDown()
        }

        void onFailure(Request req, IOException e) {
          exRef.set(e)
          latch.countDown()
        }
      })
    latch.await(10, SECONDS)
    if (exRef.get() != null) {
      throw exRef.get()
    }
    return responseRef.get().code()
  }
}

import org.apache.http.HttpResponse
import org.apache.http.concurrent.FutureCallback
import org.apache.http.message.BasicHeader
import spock.lang.Timeout

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@Timeout(5)
class ApacheHttpAsyncClientCallbackTest extends ApacheHttpAsyncClientTest {

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def isProxy = uri.fragment != null && uri.fragment.equals("proxy")
    def request = new HttpUriRequest(method, uri)
    headers.entrySet().each {
      request.addHeader(new BasicHeader(it.key, it.value))
    }

    def responseFuture = new CompletableFuture<>()

    (isProxy ? proxiedClient : client).execute(request, new FutureCallback<HttpResponse>() {

        @Override
        void completed(HttpResponse result) {
          try {
            callback?.call()
            responseFuture.complete(result.statusLine.statusCode)
          } catch (Exception e) {
            failed(e)
          }
        }

        @Override
        void failed(Exception ex) {
          responseFuture.completeExceptionally(ex)
        }

        @Override
        void cancelled() {
          responseFuture.cancel(true)
        }
      })

    return responseFuture.get(10, TimeUnit.SECONDS)
  }
}

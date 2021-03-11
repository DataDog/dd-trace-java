import org.apache.http.message.BasicHeader
import spock.lang.Timeout

import java.util.concurrent.Future

@Timeout(5)
class ApacheHttpAsyncClientNullCallbackTest extends ApacheHttpAsyncClientTest {

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def isProxy = uri.fragment != null && uri.fragment.equals("proxy")
    def request = new HttpUriRequest(method, uri)
    headers.entrySet().each {
      request.addHeader(new BasicHeader(it.key, it.value))
    }

    // The point here is to test case when callback is null - fire-and-forget style
    // So to make sure request is done we start request, wait for future to finish
    // and then call callback if present.
    Future future = (isProxy ? proxiedClient : client).execute(request, null)
    try {
      future.get()
    } finally {
      blockUntilChildSpansFinished(1)
    }
    if (callback != null) {
      callback()
    }
    return future.get().statusLine.statusCode
  }
}

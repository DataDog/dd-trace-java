import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.instrumentation.apachehttpasyncclient.ApacheHttpAsyncClientDecorator
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.nio.client.HttpAsyncClients
import org.apache.http.message.BasicHeader
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Timeout

import java.util.concurrent.Future

@Timeout(5)
class ApacheHttpAsyncClientNullCallbackTest extends HttpClientTest implements TestingGenericHttpNamingConventions.ClientV0{

  @Shared
  RequestConfig requestConfig = RequestConfig.custom()
  .setConnectTimeout(CONNECT_TIMEOUT_MS)
  .setSocketTimeout(READ_TIMEOUT_MS)
  .build()

  @AutoCleanup
  @Shared
  def client = HttpAsyncClients.custom().setDefaultRequestConfig(requestConfig).build()

  def setupSpec() {
    client.start()
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def request = new HttpUriRequest(method, uri)
    headers.entrySet().each {
      request.addHeader(new BasicHeader(it.key, it.value))
    }

    // The point here is to test case when callback is null - fire-and-forget style
    // So to make sure request is done we start request, wait for future to finish
    // and then call callback if present.
    Future future = client.execute(request, null)
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

  @Override
  CharSequence component() {
    return ApacheHttpAsyncClientDecorator.DECORATE.component()
  }

  @Override
  Integer statusOnRedirectError() {
    return 302
  }

  @Override
  boolean testRemoteConnection() {
    false // otherwise SocketTimeoutException for https requests
  }
}

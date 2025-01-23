import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.instrumentation.apachehttpclient5.ApacheHttpClientDecorator
import org.apache.hc.client5.http.async.methods.SimpleHttpRequests
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.async.HttpAsyncClients
import org.apache.hc.core5.http.HttpRequest
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.util.concurrent.TimeUnit

import static org.apache.hc.core5.reactor.IOReactorConfig.custom

abstract class ApacheHttpAsyncClient5Test<T extends HttpRequest> extends HttpClientTest {

  @Shared
  def ioReactorConfig = custom()
  .setSoTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  .build()

  @Shared
  @AutoCleanup
  def client = HttpAsyncClients.custom()
  .setIOReactorConfig(ioReactorConfig)
  .build()

  def setupSpec() {
    client.start()
  }

  @Override
  CharSequence component() {
    return ApacheHttpClientDecorator.DECORATE.component()
  }

  @Override
  Integer statusOnRedirectError() {
    return 302
  }

  @Override
  boolean testRemoteConnection() {
    false // otherwise SocketTimeoutException for https requests
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def request = SimpleHttpRequests.create(method, uri)
    request.setConfig(RequestConfig.custom().setConnectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS).build())
    headers.each { request.addHeader(it.key, it.value) }

    def future = client.execute(request, null)
    def response = future.get(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    callback?.call()
    return response.code
  }
}

class ApacheHttpAsyncClient5NamingV0Test extends ApacheHttpAsyncClient5Test implements TestingGenericHttpNamingConventions.ClientV0 {
}

class ApacheHttpAsyncClient5NamingV1ForkedTest extends ApacheHttpAsyncClient5Test implements TestingGenericHttpNamingConventions.ClientV1 {
}

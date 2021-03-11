import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.bootstrap.instrumentation.httpurlconnection.HttpUrlConnectionDecorator
import spock.lang.Timeout

import javax.net.ssl.HttpsURLConnection

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope

@Timeout(5)
class HttpUrlConnectionUseCachesFalseTest extends HttpClientTest {

  def setupSpec() {
    HttpsURLConnection.setDefaultHostnameVerifier(server.hostnameVerifier)
    HttpsURLConnection.setDefaultSSLSocketFactory(server.sslContext.socketFactory)
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def isProxy = uri.fragment != null && uri.fragment.equals("proxy")
    def url = uri.toURL()
    HttpURLConnection connection = isProxy ? url.openConnection(proxy.proxyConfig) : url.openConnection()
    try {
      connection.setRequestMethod(method)
      headers.each { connection.setRequestProperty(it.key, it.value) }
      connection.setRequestProperty("Connection", "close")
      connection.useCaches = false
      connection.connectTimeout = CONNECT_TIMEOUT_MS
      connection.readTimeout = READ_TIMEOUT_MS
      def parentSpan = activeScope()
      def stream = connection.inputStream
      assert activeScope() == parentSpan
      stream.readLines()
      stream.close()
      callback?.call()
      return connection.getResponseCode()
    } finally {
      connection.disconnect()
    }
  }

  @Override
  CharSequence component() {
    return HttpUrlConnectionDecorator.DECORATE.component()
  }

  @Override
  boolean testCircularRedirects() {
    false
  }
}

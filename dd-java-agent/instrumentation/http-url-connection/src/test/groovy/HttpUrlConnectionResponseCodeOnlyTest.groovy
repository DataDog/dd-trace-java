import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.bootstrap.instrumentation.httpurlconnection.HttpUrlConnectionDecorator
import spock.lang.Timeout

import javax.net.ssl.HttpsURLConnection

@Timeout(5)
class HttpUrlConnectionResponseCodeOnlyTest extends HttpClientTest {

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
      connection.connectTimeout = CONNECT_TIMEOUT_MS
      connection.readTimeout = READ_TIMEOUT_MS
      headers.each { connection.setRequestProperty(it.key, it.value) }
      connection.setRequestProperty("Connection", "close")
      return connection.getResponseCode()
    } finally {
      callback?.call()
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

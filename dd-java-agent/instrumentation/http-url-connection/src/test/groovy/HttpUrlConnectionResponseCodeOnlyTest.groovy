import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import spock.lang.Timeout

@Timeout(5)
class HttpUrlConnectionResponseCodeOnlyTest extends HttpUrlConnectionTest implements TestingGenericHttpNamingConventions.ClientV0 {

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    HttpURLConnection connection = uri.toURL().openConnection()
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
}

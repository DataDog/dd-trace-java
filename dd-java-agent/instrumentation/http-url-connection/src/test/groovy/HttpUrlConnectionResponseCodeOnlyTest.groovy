import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import spock.lang.Timeout

@Timeout(5)
class HttpUrlConnectionResponseCodeOnlyTest extends HttpUrlConnectionTest implements TestingGenericHttpNamingConventions.ClientV0 {

  @Override
  int doRequest(String method, URI uri, List<List<String>> headers, String body, Closure callback) {
    HttpURLConnection connection = uri.toURL().openConnection()
    try {
      connection.setRequestMethod(method)
      connection.connectTimeout = CONNECT_TIMEOUT_MS
      connection.readTimeout = READ_TIMEOUT_MS
      headers.each { connection.setRequestProperty(it[0], it[1]) }
      connection.setRequestProperty("Connection", "close")
      return connection.getResponseCode()
    } finally {
      callback?.call()
      connection.disconnect()
    }
  }
}

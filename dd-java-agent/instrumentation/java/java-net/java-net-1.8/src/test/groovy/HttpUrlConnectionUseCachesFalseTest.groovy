import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import spock.lang.Timeout

@Timeout(5)
class HttpUrlConnectionUseCachesFalseTest extends HttpUrlConnectionTest implements TestingGenericHttpNamingConventions.ClientV0 {

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    HttpURLConnection connection = uri.toURL().openConnection()
    try {
      connection.setRequestMethod(method)
      headers.each { connection.setRequestProperty(it.key, it.value) }
      connection.setRequestProperty("Connection", "close")
      connection.useCaches = false
      connection.connectTimeout = CONNECT_TIMEOUT_MS
      connection.readTimeout = READ_TIMEOUT_MS
      def parentSpan = activeSpan()
      def stream
      try {
        stream = connection.inputStream
      } catch (Exception ex) {
        stream = connection.errorStream
        ex.printStackTrace()
      }
      assert activeSpan() == parentSpan
      stream?.readLines()
      stream?.close()
      callback?.call()
      return connection.getResponseCode()
    } finally {
      connection.disconnect()
    }
  }
}

import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.bootstrap.instrumentation.httpurlconnection.HttpUrlConnectionDecorator
import spock.lang.Requires
import spock.lang.Timeout

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope

// This fails on IBM JVM because it uses different HTTPS connection types
@Requires({ !System.getProperty("java.vm.name").contains("IBM J9 VM") })
@Timeout(5)
class HttpUrlConnectionConnectFirstTest extends HttpClientTest {

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    HttpURLConnection connection = uri.toURL().openConnection()
    try {
      connection.setRequestMethod(method)
      headers.each { connection.setRequestProperty(it.key, it.value) }
      connection.setRequestProperty("Connection", "close")
      connection.connectTimeout = CONNECT_TIMEOUT_MS
      connection.readTimeout = READ_TIMEOUT_MS
      def parentSpan = activeScope()
      connection.connect() // test connect before getting stream
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

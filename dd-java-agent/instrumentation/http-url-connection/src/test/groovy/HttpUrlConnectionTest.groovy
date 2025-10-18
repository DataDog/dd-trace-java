import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.httpurlconnection.HttpUrlConnectionDecorator
import spock.lang.Ignore
import spock.lang.IgnoreIf
import spock.lang.Timeout
import sun.net.www.protocol.https.HttpsURLConnectionImpl

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

@Timeout(5)
abstract class HttpUrlConnectionTest extends HttpClientTest {

  static final RESPONSE = "Hello."
  static final STATUS = 200

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    HttpURLConnection connection = uri.toURL().openConnection()
    try {
      connection.setRequestMethod(method)
      headers.each { connection.setRequestProperty(it.key, it.value) }
      connection.setRequestProperty("Connection", "close")
      connection.useCaches = true
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

  @Override
  CharSequence component() {
    return HttpUrlConnectionDecorator.HTTP_URL_CONNECTION
  }

  @Override
  boolean testCircularRedirects() {
    false
  }

  @Ignore
  def "trace request with propagation (useCaches: #useCaches)"() {
    setup:
    def url = server.address.resolve("/success").toURL()
    injectSysConfig(HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "$renameService")

    when:
    runUnderTrace("someTrace") {
      HttpURLConnection connection = url.openConnection()
      connection.useCaches = useCaches
      assert activeSpan() != null
      def stream = connection.inputStream
      def lines = stream.readLines()
      stream.close()
      assert connection.getResponseCode() == STATUS
      assert lines == [RESPONSE]

      // call again to ensure the cycling is ok
      connection = url.openConnection()
      connection.useCaches = useCaches
      assert activeSpan() != null
      assert connection.getResponseCode() == STATUS // call before input stream to test alternate behavior
      connection.inputStream
      stream = connection.inputStream // one more to ensure state is working
      lines = stream.readLines()
      stream.close()
      assert lines == [RESPONSE]
    }

    then:
    assertTraces(3) {
      server.distributedRequestTrace(it, trace(2)[2])
      server.distributedRequestTrace(it, trace(2)[1])
      trace(3) {
        span {
          operationName "someTrace"
          parent()
          errored false
          tags {
            defaultTags()
          }
        }
        span {
          if (renameService) {
            serviceName "localhost"
          }
          operationName operation()
          resourceName "GET $url.path"
          spanType DDSpanTypes.HTTP_CLIENT
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "http-url-connection"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" server.address.port
            "$Tags.HTTP_URL" "$url"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" STATUS
            defaultTags()
          }
        }
        span {
          if (renameService) {
            serviceName "localhost"
          }
          operationName operation()
          resourceName "GET $url.path"
          spanType DDSpanTypes.HTTP_CLIENT
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "http-url-connection"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" server.address.port
            "$Tags.HTTP_URL" "$url"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" STATUS
            defaultTags()
          }
        }
      }
    }

    where:
    useCaches << [false, true]
    renameService << [true, false]
  }

  @Ignore
  def "trace request without propagation (useCaches: #useCaches)"() {
    setup:
    def url = server.address.resolve("/success").toURL()
    injectSysConfig(HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "$renameService")

    when:
    runUnderTrace("someTrace") {
      HttpURLConnection connection = url.openConnection()
      connection.useCaches = useCaches
      connection.addRequestProperty("is-dd-server", "false")
      assert activeSpan() != null
      def stream = connection.inputStream
      connection.inputStream // one more to ensure state is working
      def lines = stream.readLines()
      stream.close()
      assert connection.getResponseCode() == STATUS
      assert lines == [RESPONSE]

      // call again to ensure the cycling is ok
      connection = url.openConnection()
      connection.useCaches = useCaches
      connection.addRequestProperty("is-dd-server", "false")
      assert activeSpan() != null
      assert connection.getResponseCode() == STATUS // call before input stream to test alternate behavior
      stream = connection.inputStream
      lines = stream.readLines()
      stream.close()
      assert lines == [RESPONSE]
    }

    then:
    assertTraces(1) {
      trace(3) {
        span {
          operationName "someTrace"
          parent()
          errored false
          tags {
            defaultTags()
          }
        }
        span {
          if (renameService) {
            serviceName "localhost"
          }
          operationName operation()
          resourceName "GET $url.path"
          spanType DDSpanTypes.HTTP_CLIENT
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "http-url-connection"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" server.address.port
            "$Tags.HTTP_URL" "$url"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" STATUS
            defaultTags()
          }
        }
        span {
          if (renameService) {
            serviceName "localhost"
          }
          operationName operation()
          resourceName "GET $url.path"
          spanType DDSpanTypes.HTTP_CLIENT
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "http-url-connection"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" server.address.port
            "$Tags.HTTP_URL" "$url"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" STATUS
            defaultTags()
          }
        }
      }
    }

    where:
    useCaches << [false, true]
    renameService << [false, true]
  }

  @Ignore
  def "test broken API usage"() {
    setup:
    def url = server.address.resolve("/success").toURL()
    injectSysConfig(HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "$renameService")

    when:
    HttpURLConnection conn = runUnderTrace("someTrace") {
      HttpURLConnection connection = url.openConnection()
      connection.setRequestProperty("Connection", "close")
      connection.addRequestProperty("is-dd-server", "false")
      assert activeSpan() != null
      assert connection.getResponseCode() == STATUS
      return connection
    }

    then:
    assertTraces(1) {
      trace(2) {
        span {
          operationName "someTrace"
          parent()
          errored false
          tags {
            defaultTags()
          }
        }
        span {
          if (renameService) {
            serviceName "localhost"
          }
          operationName operation()
          resourceName "GET $url.path"
          spanType DDSpanTypes.HTTP_CLIENT
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "http-url-connection"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" server.address.port
            "$Tags.HTTP_URL" "$url"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" STATUS
            defaultTags()
          }
        }
      }
    }

    cleanup:
    conn.disconnect()

    where:
    iteration << (1..10)
    renameService = (iteration % 2 == 0) // alternate even/odd
  }

  @Ignore
  def "test post request"() {
    setup:
    def url = server.address.resolve("/success").toURL()
    injectSysConfig(HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "$renameService")

    when:
    runUnderTrace("someTrace") {
      HttpURLConnection connection = url.openConnection()
      connection.setRequestMethod("POST")

      String urlParameters = "q=ASDF&w=&e=&r=12345&t="

      // Send post request
      connection.setDoOutput(true)
      DataOutputStream wr = new DataOutputStream(connection.getOutputStream())
      wr.writeBytes(urlParameters)
      wr.flush()
      wr.close()

      assert connection.getResponseCode() == STATUS

      def stream = connection.inputStream
      def lines = stream.readLines()
      stream.close()
      assert lines == [RESPONSE]
    }

    then:
    assertTraces(2) {
      server.distributedRequestTrace(it, trace(1)[1])
      trace(2) {
        span {
          operationName "someTrace"
          parent()
          errored false
          tags {
            defaultTags()
          }
        }
        span {
          if (renameService) {
            serviceName "localhost"
          }
          operationName operation()
          resourceName "POST $url.path"
          spanType DDSpanTypes.HTTP_CLIENT
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "http-url-connection"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" server.address.port
            "$Tags.HTTP_URL" "$url"
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" STATUS
            defaultTags()
          }
        }
      }
    }

    where:
    renameService << [false, true]
  }

  // This test makes no sense on IBM JVM because there is no HttpsURLConnectionImpl class there
  @IgnoreIf({
    System.getProperty("java.vm.name").contains("IBM J9 VM") ||
      // TODO Java 17: we can't access HttpsURLConnectionImpl on Java 17
      new BigDecimal(System.getProperty("java.specification.version")).isAtLeast(17.0)
  })
  def "Make sure we can load HttpsURLConnectionImpl"() {
    when:
    def instance = new HttpsURLConnectionImpl(null, null, null)

    then:
    instance != null
  }
}

class HttpUrlConnectionV0ForkedTest extends HttpUrlConnectionTest implements TestingGenericHttpNamingConventions.ClientV0 {}

class HttpUrlConnectionV1ForkedTest extends HttpUrlConnectionTest implements TestingGenericHttpNamingConventions.ClientV1 {
}

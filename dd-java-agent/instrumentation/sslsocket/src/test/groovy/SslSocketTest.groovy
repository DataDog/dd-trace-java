package test

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.usm.UsmExtractor
import datadog.trace.bootstrap.instrumentation.usm.UsmMessageFactory
import spock.lang.AutoCleanup
import spock.lang.Shared

import javax.net.ssl.HttpsURLConnection
import java.lang.reflect.Field

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class SslSocketTest extends InstrumentationSpecification {
  @AutoCleanup
  @Shared
  def server = httpServer {
    handlers {
      prefix("success") {
        response.status(200).send()
      }
    }
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.usm.enabled", "true")
  }

  def "simple HTTPS request"() {
    setup:
    HttpsURLConnection.setDefaultSSLSocketFactory(server.sslContext.getSocketFactory())
    URL url = server.getSecureAddress().resolve("/success").toURL()

    HttpsURLConnection conn = (HttpsURLConnection) url.openConnection()
    conn.setRequestMethod(method)
    conn.setRequestProperty("Content-Type", "text/plain")
    conn.setDoOutput(true)
    conn.connect()

    // Mock message factory
    Class msgItfcCls = Class.forName("datadog.trace.bootstrap.instrumentation.usm.UsmMessageFactory")
    Class msgSupplierCls = msgItfcCls.getClasses()[0]
    Field msgSupplierField = msgSupplierCls.getDeclaredField("SUPPLIER")
    msgSupplierField.setAccessible(true)
    UsmMessageFactory factoryMock = Mock(UsmMessageFactory)
    msgSupplierField.set(null, factoryMock)

    // Mock extractor
    Class extractorItfcCls = Class.forName("datadog.trace.bootstrap.instrumentation.usm.UsmExtractor")
    Class extractorSupplierCls = extractorItfcCls.getClasses()[0]
    Field extractorSupplierField = extractorSupplierCls.getDeclaredField("SUPPLIER")
    extractorSupplierField.setAccessible(true)
    UsmExtractor extractorMock = Mock(UsmExtractor)
    extractorSupplierField.set(null, extractorMock)

    when:
    int status = conn.getResponseCode()

    then:
    status == 200

    // 50 * factoryMock.getRequestMessage(_, { byte[] buffer ->
    //   String str = new String(buffer)
    //   boolean match = str.length() > 0 && str.startsWith("POST")
    //   println("Intermediate string: $str")
    //   println("Matching: $match\n")
    //   return match
    // }, _, _)
    2 * factoryMock.getRequestMessage(_, {
      verifyAll(it, byte[]) {
        def str = new String(it)
        str.length() > 0
        str.startsWith("POST") || str.startsWith("HTTP")
      }}, _, _)
    (1.._) * extractorMock.send(null) // `getRequestMessage` mock returns `null` so we expect to get it in send

    where:
    method = "POST"
  }
}

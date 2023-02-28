package test

import java.lang.Class
import java.lang.reflect.Field
import javax.net.ssl.HttpsURLConnection

import datadog.trace.bootstrap.instrumentation.api.UsmMessageFactory
import datadog.trace.bootstrap.instrumentation.api.UsmExtractor
import datadog.trace.agent.test.AgentTestRunner
import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

import spock.lang.AutoCleanup
import spock.lang.Shared


class SslSocketImplTest extends AgentTestRunner {
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

    HttpsURLConnection conn = (HttpsURLConnection)url.openConnection()
    conn.setRequestMethod(method)
    conn.setRequestProperty("Content-Type", "text/plain")
    conn.setDoOutput(true)
    conn.connect()

    // Mock message factory
    Class msgItfcCls = Class.forName("datadog.trace.bootstrap.instrumentation.api.UsmMessageFactory")
    Class msgSupplierCls = msgItfcCls.getClasses()[0]
    Field msgSupplierField = msgSupplierCls.getDeclaredField("SUPPLIER")
    msgSupplierField.setAccessible(true)
    UsmMessageFactory factoryMock = Mock()
    msgSupplierField.set(null, factoryMock)

    // Mock extractor
    Class extractorItfcCls = Class.forName("datadog.trace.bootstrap.instrumentation.api.UsmExtractor")
    Class extractorSupplierCls = extractorItfcCls.getClasses()[0]
    Field extractorSupplierField = extractorSupplierCls.getDeclaredField("SUPPLIER")
    extractorSupplierField.setAccessible(true)
    UsmExtractor extractorMock = Mock()
    extractorSupplierField.set(null, extractorMock)

    when:
    int status = conn.getResponseCode()

    then:
    status == 200
    2 * factoryMock.getRequestMessage(*_) // expect 2 calls: one request, one response
    2 * extractorMock.send(null) // `getRequestMessage` mock returns `null` so we expect to get it in send

    where:
    method = "POST"
  }
}

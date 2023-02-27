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
      all {
        response.status(200).send(responseBody.get())
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
    URL url = server.getSecureAddress().resolve("/foo").toURL()

    HttpsURLConnection conn = (HttpsURLConnection)url.openConnection()
    conn.setRequestMethod(method)
    conn.setRequestProperty("Content-Type", "text/plain")
    conn.setDoOutput(true);
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
    conn.getOutputStream().write(body);

    then:
    assert conn.connected
    1 * factoryMock.getRequestMessage(*_)
    1 * extractorMock.send(_)

    where:
    method = "POST"
    body = (1..1000).join(" ").getBytes("utf-8")
  }
}

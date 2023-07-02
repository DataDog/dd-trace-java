package test

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.usm.Extractor
import datadog.trace.bootstrap.instrumentation.usm.MessageEncoder
import spock.lang.AutoCleanup
import spock.lang.Shared

import javax.net.ssl.HttpsURLConnection
import java.lang.reflect.Field
import java.nio.ByteBuffer
import java.nio.charset.Charset

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class SslSocketTest extends AgentTestRunner {
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

  def "simple sync HTTPS request"() {
    setup:
    HttpsURLConnection.setDefaultSSLSocketFactory(server.sslContext.getSocketFactory())
    URL url = server.getSecureAddress().resolve("/success").toURL()
    int CONNECTION_STRUCT_SIZE = 48
    HttpsURLConnection conn = (HttpsURLConnection) url.openConnection()
    conn.setRequestMethod(method)
    conn.setRequestProperty("Content-Type", "text/plain")
    conn.setDoOutput(true)
    conn.connect()

    // Mock extractor
    Class extractorItfcCls = Class.forName("datadog.trace.bootstrap.instrumentation.usm.Extractor")
    Class extractorSupplierCls = extractorItfcCls.getClasses()[0]
    Field extractorSupplierField = extractorSupplierCls.getDeclaredField("SUPPLIER")
    extractorSupplierField.setAccessible(true)
    Extractor extractorMock = Mock(Extractor)
    extractorSupplierField.set(null, extractorMock)

    when:
    int status = conn.getResponseCode()

    then:
    status == 200

    2 * extractorMock.send({
      verifyAll(it, ByteBuffer) {
        it.position(0)
        //validate message type
        it.get() == (byte)MessageEncoder.MessageType.SYNCHRONOUS_PAYLOAD.ordinal()

        //skip connection struct
        it.position(1 + CONNECTION_STRUCT_SIZE)

        //read payload size
        def payloadSize = it.getInt()
        Charset charset = Charset.defaultCharset()

        //read the payload as string
        String str = charset.decode(it).toString()

        //validate the payload size is equivalent to what was encoded
        str.length() == payloadSize

        //check the message is an HTTP POST Message
        str.startsWith("POST") || str.startsWith("HTTP")
      }})

    where:
    method = "POST"
  }

}


import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.WithHttpServer
import datadog.trace.bootstrap.instrumentation.api.Tags
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.internal.Version
import org.springframework.boot.SpringApplication
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import spring.EchoConfig
import spring.EchoEndpoint

import static datadog.trace.api.DDSpanTypes.*
import static java.util.Collections.singletonMap

class SpringWebServiceTest extends WithHttpServer<ConfigurableApplicationContext> {
  @Override
  HttpServer server() {
    return new SpringBootServer()
  }

  /*
   * VersionNamingTest not used.
   */

  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return null
  }

  @Override
  String operation() {
    return null
  }

  def 'test web service'() {
    setup:
    def soapMessage = failed ? 'Fail' : 'Hello'
    def url = address.resolve('echo-server/services').toURL()
    def request = new Request.Builder()
      .url(url)
      .post(RequestBody.create(MediaType.get('text/xml'), """
        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                          xmlns:tns="http://www.springframework.org/spring-ws/samples/echo">
            <soapenv:Header/>
            <soapenv:Body>
                <tns:echoRequest>${soapMessage}</tns:echoRequest>
            </soapenv:Body>
        </soapenv:Envelope>"""))
      .build()

    when:
    def response = client.newCall(request).execute()

    then:
    if (failed) {
      response.code() == 500
    } else {
      response.successful
      response.body().string() == '<SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/"><SOAP-ENV:Header/><SOAP-ENV:Body><echoResponse xmlns="http://www.springframework.org/spring-ws/samples/echo">Hello</echoResponse></SOAP-ENV:Body></SOAP-ENV:Envelope>'
    }
    assertTraces(1) {
      trace(2) {
        span(0) {
          parent()
          operationName('servlet.request')
          spanType(HTTP_SERVER)
          errored(failed)
          tags {
            defaultTags()
            "$Tags.COMPONENT" "tomcat-server"
            "$Tags.SPAN_KIND" "$Tags.SPAN_KIND_SERVER"
            "$Tags.HTTP_CLIENT_IP" "127.0.0.1"
            "$Tags.HTTP_HOSTNAME" "localhost"
            "$Tags.HTTP_METHOD" "POST"
            "$Tags.HTTP_STATUS" { failed ? "500" : "200" }
            "$Tags.HTTP_URL" "$url"
            "$Tags.HTTP_USER_AGENT" "${Version.userAgent()}"
            "$Tags.PEER_HOST_IPV4" { true }
            "$Tags.PEER_HOST_IPV6" { true }
            "$Tags.PEER_PORT" { true }
            "servlet.context" "/"
            "servlet.path" "/echo-server"
            if (failed) {
              errorTags(RuntimeException, 'Must fail for test purpose')
            }
          }
        }
        span(1) {
          childOfPrevious()
          operationName('spring.handler')
          spanType(HTTP_SERVER)
        }
      }
    }

    where:
    failed << [true, false]
  }

  class SpringBootServer implements HttpServer {
    def port = 0
    def context
    def app = new SpringApplication(EchoConfig, EchoEndpoint)

    @Override
    void start() {
      app.setDefaultProperties(singletonMap("server.port", 0))
      context = app.run() as ServletWebServerApplicationContext
      this.port = context.webServer.port
      assert this.port > 0
    }

    @Override
    void stop() {
      context.close()
    }

    @Override
    URI address() {
      return new URI("http://localhost:${this.port}/")
    }

    @Override
    String toString() {
      return this.class.name
    }
  }
}

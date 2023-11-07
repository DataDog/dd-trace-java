import com.datadog.iast.test.IastHttpServerTest
import datadog.trace.agent.test.base.HttpServer
import groovy.transform.CompileDynamic
import okhttp3.FormBody
import okhttp3.Request
import org.springframework.boot.SpringApplication
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import server.TestApp
import server.TestController

import static java.util.Collections.singletonMap

@CompileDynamic
class IastTest extends IastHttpServerTest<ConfigurableApplicationContext> {

  void 'test'(){
    when:
    final url = "${address}apache_ssrf"
    final body = new FormBody.Builder().add('url', 'https://dd.datad0g.com/').add('method','8').build()
    final request = new Request.Builder().url(url).post(body).build()
    final response = client.newCall(request).execute()

    then:
    assert response.successful

    when:
    def toc = getFinReqTaintedObjects()

    then:
    toc.hasTaintedObject {
      value 'https://dd.datad0g.com/'
    }

    /*
     when:
     def vulnerabilities = getVulnerabilities()
     then:
     assert vulnerabilities.size() == 1
     assert vulnerabilities[0].type == 'SSRF'
     assert vulnerabilities[0].location.path == 'TestController'
     assert vulnerabilities[0].location.line == 10
     assert vulnerabilities[0].location.sourceType == 'JAVA'
     */
  }

  @Override
  HttpServer server() {
    return new SpringBootServer()
  }

  class SpringBootServer implements HttpServer {
    def context
    def port = 0
    def app = new SpringApplication(TestApp, TestController)

    @Override
    void start() {
      app.setDefaultProperties(singletonMap("server.port", 0))
      context = app.run() as ServletWebServerApplicationContext
      port = context.webServer.port
      assert port > 0
    }

    @Override
    void stop() {
      context.close()
    }

    @Override
    URI address() {
      return new URI("http://localhost:$port/")
    }

    @Override
    String toString() {
      return this.class.name
    }
  }
}

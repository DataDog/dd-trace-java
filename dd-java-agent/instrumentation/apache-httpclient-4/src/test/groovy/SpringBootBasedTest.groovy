import boot.AppConfig
import boot.SpringBootServer
import com.datadog.iast.test.IastHttpServerTest
import datadog.trace.agent.test.base.HttpServer
import okhttp3.FormBody
import okhttp3.Request
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext

class SpringBootBasedTest extends IastHttpServerTest<ConfigurableApplicationContext> {

  SpringApplication application() {
    new SpringApplication(AppConfig, SsrfController)
  }

  String getServletContext() {
    return "context"
  }

  @Override
  HttpServer server() {
    new SpringBootServer(application(), servletContext)
  }

  void 'ssrf is present'() {
    setup:
    def expected = 'http://dd.datad0g.com/test/'+suite.executedMethod
    final url = address.toString() + '/ssrf/execute'
    final body = new FormBody.Builder()
      .add('url', expected)
      .add('client', suite.clientImplementation)
      .add('method', suite.executedMethod)
      .add('requestType', suite.requestType)
      .add('scheme', suite.scheme)
      .build()
    final request = new Request.Builder().url(url).post(body).build()
    if(suite.scheme == 'https') {
      expected = expected.replace('http', 'https')
    }

    when:
    def response = client.newCall(request).execute()

    then:
    response.successful

    def to = getFinReqTaintedObjects()
    assert to != null
/*

    hasVulnerability {
      vul ->
        vul.type == 'SSRF'
          && vul.location.path == 'datadog.smoketest.springboot.SsrfController'
          && vul.evidence.valueParts[0].value == expected


    }
*/


    where:
    suite << createTestSuite()
  }

  private Iterable<TestSuite> createTestSuite() {
    final result = []
    for (SsrfController.Client client : SsrfController.Client.values()) {
      for (SsrfController.ExecuteMethod method : SsrfController.ExecuteMethod.values()) {
        if (method.name().startsWith('HOST')) {
          result.add(createTestSuite(client, method, SsrfController.Request.HttpRequest, 'http'))
          result.add(createTestSuite(client, method, SsrfController.Request.HttpRequest, 'https'))
        }
        result.add(createTestSuite(client, method, SsrfController.Request.HttpUriRequest, 'http'))
      }
    }
    return result as Iterable<TestSuite>
  }

  private TestSuite createTestSuite(client, method, request, scheme) {
    return new TestSuite(
      description: "ssrf is present for ${client} client and ${method} method with ${request} and ${scheme} scheme",
      executedMethod: method.name(),
      clientImplementation: client.name(),
      requestType: request.name(),
      scheme: scheme
    )
  }

  private static class TestSuite {
    String description
    String executedMethod
    String clientImplementation
    String requestType
    String scheme

    @Override
    String toString() {
      return "IAST apache httpclient 4 test suite: ${description}"
    }
  }

}

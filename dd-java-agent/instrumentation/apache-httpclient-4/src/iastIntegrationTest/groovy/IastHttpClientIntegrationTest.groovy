import com.datadog.iast.model.VulnerabilityType
import com.datadog.iast.test.IastHttpServerTest
import datadog.trace.agent.test.base.HttpServer
import okhttp3.Request

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class IastHttpClientIntegrationTest extends IastHttpServerTest<HttpServer> {

  static final CLIENTS = [
    'org.apache.http.impl.client.AutoRetryHttpClient',
    'org.apache.http.impl.client.ContentEncodingHttpClient',
    'org.apache.http.impl.client.DefaultHttpClient',
    'org.apache.http.impl.client.SystemDefaultHttpClient'
  ]

  @Override
  HttpServer server() {
    final controller = new SsrfController()
    return httpServer {
      handlers {
        prefix('/ssrf/execute') {
          final msg = controller.apacheSsrf(
          (String) request.getParameter('url'),
          (String) request.getParameter('clientClassName'),
          (String) request.getParameter('method'),
          (String) request.getParameter('requestType'),
          (String) request.getParameter('scheme')
          )
          response.status(200).send(msg)
        }
      }
    }.asHttpServer()
  }

  void 'ssrf is present'() {
    setup:
    def expected = 'http://inexistent/test/' + suite.executedMethod
    if (suite.scheme == 'https') {
      expected = expected.replace('http', 'https')
    }
    final url = address.toString() + 'ssrf/execute' + '?url=' + expected + '&clientClassName=' + suite.clientImplementation + '&method=' + suite.executedMethod + '&requestType=' + suite.requestType + '&scheme=' + suite.scheme
    final request = new Request.Builder().url(url).get().build()


    when:
    def response = client.newCall(request).execute()

    then:
    response.successful
    TEST_WRITER.waitForTraces(1)
    def to = getFinReqTaintedObjects()
    assert to != null
    hasVulnerability (
    vul ->
    vul.type == VulnerabilityType.SSRF
    && vul.evidence.value == expected
    )


    where:
    suite << createTestSuite()
  }

  private Iterable<TestSuite> createTestSuite() {
    final result = []
    for (String client : getAvailableClientsClassName()) {
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
    clientImplementation: client,
    requestType: request.name(),
    scheme: scheme
    )
  }

  private String[] getAvailableClientsClassName() {
    def availableClients = []
    CLIENTS.each {
      try {
        Class.forName(it)
        availableClients.add(it)
      } catch (ClassNotFoundException e) {
        // ignore
      }
    }
    return availableClients
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

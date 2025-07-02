import com.datadog.iast.model.Evidence
import com.datadog.iast.model.VulnerabilityType
import com.datadog.iast.test.IastHttpServerTest
import datadog.trace.agent.test.base.HttpServer
import foo.bar.VulnerableUrlBuilder
import okhttp3.HttpUrl
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
          VulnerableUrlBuilder.url(request),
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

  void 'ssrf is present: #suite'() {
    setup:
    final url = suite.url(address)
    final request = new Request.Builder().url(url).get().build()


    when:
    def response = client.newCall(request).execute()

    then:
    response.successful
    TEST_WRITER.waitForTraces(1)
    def to = getFinReqTaintedObjects()
    assert to != null
    hasVulnerability(vul -> vul.type == VulnerabilityType.SSRF && suite.evidenceMatches(vul.evidence))

    where:
    suite << createTestSuite()
  }

  private Iterable<TestSuite> createTestSuite() {
    final result = []
    for (String client : getAvailableClientsClassName()) {
      for (SsrfController.ExecuteMethod method : SsrfController.ExecuteMethod.values()) {
        if (method.name().startsWith('HOST')) {
          result.addAll(createTestSuite(client, method, SsrfController.Request.HttpRequest, 'http'))
          result.addAll(createTestSuite(client, method, SsrfController.Request.HttpRequest, 'https'))
        }
        result.addAll(createTestSuite(client, method, SsrfController.Request.HttpUriRequest, 'http'))
      }
    }
    return result as Iterable<TestSuite>
  }

  private List<TestSuite> createTestSuite(client, method, request, scheme) {
    return TaintedTarget.values().collect {
      new TestSuite(
      description: "Tainted ${it.name()} with ${client} client and ${method} method with ${request} and ${scheme} scheme",
      executedMethod: method.name(),
      clientImplementation: client,
      requestType: request.name(),
      scheme: scheme,
      tainted: it
      )
    }
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
    TaintedTarget tainted

    HttpUrl url(final URI address) {
      final builder = new HttpUrl.Builder()
      .scheme(address.getScheme())
      .host(address.getHost())
      .port(address.getPort())
      .addPathSegments('ssrf/execute')
      .addQueryParameter('clientClassName', clientImplementation)
      .addQueryParameter('method', executedMethod)
      .addQueryParameter('requestType', requestType)
      .addQueryParameter('scheme', scheme)
      tainted.addTainted(builder, this)
      return builder.build()
    }

    boolean evidenceMatches(Evidence evidence) {
      return tainted.matches(evidence, this)
    }

    @Override
    String toString() {
      return description
    }
  }

  private static enum TaintedTarget {
    URL{
      void addTainted(HttpUrl.Builder builder, TestSuite suite) {
        builder.addQueryParameter('url', suite.scheme + '://inexistent/test?1=1')
      }

      boolean matches(Evidence evidence, TestSuite suite) {
        return assertTainted(evidence, 'url', suite.scheme + '://inexistent/test?1=1')
      }
    },
    SCHEME{
      void addTainted(HttpUrl.Builder builder, TestSuite suite) {
        // already added by the test
      }

      boolean matches(Evidence evidence, TestSuite suite) {
        return assertTainted(evidence, 'scheme', suite.scheme)
      }
    },
    HOST{
      void addTainted(HttpUrl.Builder builder, TestSuite suite) {
        builder.addQueryParameter('host', 'inexistent')
      }

      boolean matches(Evidence evidence, TestSuite suite) {
        return assertTainted(evidence, 'host', 'inexistent')
      }
    },
    PATH{
      void addTainted(HttpUrl.Builder builder, TestSuite suite) {
        builder.addQueryParameter('path', '/test')
      }

      boolean matches(Evidence evidence, TestSuite suite) {
        return assertTainted(evidence, 'path', '/test')
      }
    },
    QUERY{
      void addTainted(HttpUrl.Builder builder, TestSuite suite) {
        builder.addQueryParameter('query', '?1=1')
      }

      boolean matches(Evidence evidence, TestSuite suite) {
        return assertTainted(evidence, 'query', '?1=1')
      }
    }

    abstract void addTainted(HttpUrl.Builder builder, TestSuite suite)

    abstract boolean matches(Evidence evidence, TestSuite suite)

    protected static boolean assertTainted(Evidence evidence, String sourceName, String expected) {
      final value = evidence.value
      final range = evidence.ranges[0]
      if (range.source.name != sourceName) {
        return false
      }
      final tainted = value.substring(range.start, range.start + range.length)
      return tainted == expected
    }
  }
}

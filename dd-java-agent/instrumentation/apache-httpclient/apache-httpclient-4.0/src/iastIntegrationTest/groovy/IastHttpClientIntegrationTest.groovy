import com.datadog.iast.model.Evidence
import com.datadog.iast.model.VulnerabilityType
import com.datadog.iast.test.IastHttpServerTest
import datadog.trace.agent.test.base.HttpServer
import foo.bar.VulnerableUrlBuilder
import okhttp3.HttpUrl
import okhttp3.Request
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder

import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class IastHttpClientIntegrationTest extends IastHttpServerTest<Server> {

  static final CLIENTS = [
    'org.apache.http.impl.client.AutoRetryHttpClient',
    'org.apache.http.impl.client.ContentEncodingHttpClient',
    'org.apache.http.impl.client.DefaultHttpClient',
    'org.apache.http.impl.client.SystemDefaultHttpClient'
  ]

  @Override
  HttpServer server() {
    final controller = new SsrfController()

    // Use _raw_ jetty API, so the jetty instrumentation is applied
    // (the :dd-java-agent:testing has a shadowed jetty server.)
    return new HttpServer() {
      Server jettyServer
      int port

      @Override
      void start() {
        jettyServer = new Server(0) // 0 = random port

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS)
        context.setContextPath('/')
        jettyServer.setHandler(context)

        // Add servlet for the /ssrf/execute endpoint
        context.addServlet(new ServletHolder(new HttpServlet() {
          @Override
          protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
            final msg = controller.apacheSsrf(
            VulnerableUrlBuilder.url(req),
            req.getParameter('clientClassName'),
            req.getParameter('method'),
            req.getParameter('requestType'),
            req.getParameter('scheme')
            )
            resp.setStatus(200)
            resp.writer.write(msg)
          }
        }), '/ssrf/execute')

        jettyServer.start()
        port = ((ServerConnector) jettyServer.connectors[0]).localPort
      }

      @Override
      void stop() {
        jettyServer?.stop()
      }

      @Override
      URI address() {
        return new URI("http://localhost:${port}/")
      }
    }
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

    cleanup:
    response?.close()

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
    URL {
      void addTainted(HttpUrl.Builder builder, TestSuite suite) {
        builder.addQueryParameter('url', suite.scheme + '://inexistent/test?1=1')
      }

      boolean matches(Evidence evidence, TestSuite suite) {
        return assertTainted(evidence, 'url', suite.scheme + '://inexistent/test?1=1')
      }
    },
    SCHEME {
      void addTainted(HttpUrl.Builder builder, TestSuite suite) {
        // already added by the test
      }

      boolean matches(Evidence evidence, TestSuite suite) {
        return assertTainted(evidence, 'scheme', suite.scheme)
      }
    },
    HOST {
      void addTainted(HttpUrl.Builder builder, TestSuite suite) {
        builder.addQueryParameter('host', 'inexistent')
      }

      boolean matches(Evidence evidence, TestSuite suite) {
        return assertTainted(evidence, 'host', 'inexistent')
      }
    },
    PATH {
      void addTainted(HttpUrl.Builder builder, TestSuite suite) {
        builder.addQueryParameter('path', '/test')
      }

      boolean matches(Evidence evidence, TestSuite suite) {
        return assertTainted(evidence, 'path', '/test')
      }
    },
    QUERY {
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

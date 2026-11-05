import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.base.WithHttpServer
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.agent.test.naming.TestingOtelHttpSemantics
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.test.util.ThreadUtils
import datadog.trace.instrumentation.pekkohttp.PekkoHttpServerDecorator
import okhttp3.Request
import spock.lang.Shared

import java.util.concurrent.atomic.AtomicInteger

abstract class PekkoHttpServerInstrumentationTest extends HttpServerTest<PekkoHttpTestWebServer> {

  @Override
  String component() {
    return PekkoHttpServerDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    return "pekko-http.request"
  }

  @Override
  boolean testExceptionBody() {
    false
  }

  @Override
  boolean hasPeerInformation() {
    return false
  }

  @Override
  boolean hasExtraErrorInformation() {
    return true
  }

  @Override
  boolean changesAll404s() {
    true
  }

  //@Ignore("https://github.com/DataDog/dd-trace-java/pull/5213")
  @Override
  boolean testBadUrl() {
    false
  }

  @Shared
  def totalInvocations = 200

  @Shared
  AtomicInteger counter = new AtomicInteger(0)

  void doAndValidateRequest(int id) {
    def type = id & 1 ? "p" : "f"
    String url = address.resolve("/injected-id/${type}ing/$id")
    def traceId = totalInvocations + id
    def request = new Request.Builder().url(url).get().header("x-datadog-trace-id", traceId.toString()).build()
    def response = client.newCall(request).execute()
    def responseBodyStr = response.body().string()
    assert responseBodyStr == "${type}ong $id -> $traceId"
    assert response.code() == 200
  }

  def "propagate trace id when we ping pekko-http concurrently"() {
    expect:
    ThreadUtils.runConcurrently(10, totalInvocations, {
      def id = counter.incrementAndGet()
      doAndValidateRequest(id)
    })

    and:
    TEST_WRITER.waitForTraces(totalInvocations)
  }
}

abstract class PekkoHttpServerInstrumentationSyncTest extends PekkoHttpServerInstrumentationTest {
  @Override
  HttpServer server() {
    return new PekkoHttpTestWebServer(PekkoHttpTestWebServer.BindAndHandleSync())
  }

  @Override
  String expectedOperationName() {
    return operation()
  }
}

class PekkoHttpServerInstrumentationSyncV0ForkedTest extends PekkoHttpServerInstrumentationSyncTest {
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
    return "pekko-http.request"
  }
}

class PekkoHttpServerInstrumentationSyncV1ForkedTest extends PekkoHttpServerInstrumentationSyncTest implements TestingGenericHttpNamingConventions.ServerV1 {
}

abstract class PekkoHttpRouteOtelSemanticsTest extends WithHttpServer<PekkoHttpTestWebServer> implements TestingGenericHttpNamingConventions.ServerV0, TestingOtelHttpSemantics {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TraceInstrumentationConfig.LEGACY_CONTEXT_MANAGER_ENABLED, "true")
  }

  @Override
  HttpServer server() {
    new PekkoHttpTestWebServer(PekkoHttpTestWebServer.BindAndHandle())
  }

  def "matched paths produce stable server routes"() {
    when:
    def response = client.newCall(new Request.Builder().url(address.resolve(path).toString()).build()).execute()
    assert response.code() == 200
    response.close()
    TEST_WRITER.waitForTraces(1)

    then:
    def serverSpan = TEST_WRITER.flatten().find { it.getTag('component')?.toString() == 'pekko-http-server' }
    serverSpan != null
    serverSpan.getTag('http.route') == route
    serverSpan.resourceName.toString() == "GET $route"

    where:
    path                            | route
    '/success'                      | '/success'
    '/directive-rejection/value?required=yes' | '/directive-rejection/{param}'
    '/items/a%20b'                  | '/items/{param}'
    '/items/a%2Fb'                  | '/items/{param}'
    '/items/a%25b'                  | '/items/{param}'
    '/query?some=value'             | '/query'
    '/injected-id/ping/1'           | '/injected-id/ping/{param}'
    '/injected-id/ping/2'           | '/injected-id/ping/{param}'
    '/injected-id/fing/3'           | '/injected-id/fing/{param}'
    '/route-async/4/detail/5'       | '/route-async/{param}/detail/{param}'
    '/route-alternative/6/accepted' | '/route-alternative/{param}/accepted'
  }

  def "rejected paths do not produce server routes"() {
    when:
    def unmatched = client.newCall(new Request.Builder().url(address.resolve('/no-such-route').toString()).build()).execute()
    assert unmatched.code() == 404
    unmatched.close()
    TEST_WRITER.waitForTraces(1)
    def unmatchedSpan = TEST_WRITER.flatten().find { it.getTag('component')?.toString() == 'pekko-http-server' }
    TEST_WRITER.clear()
    def response = client.newCall(new Request.Builder().url(address.resolve(path).toString()).build()).execute()
    assert response.code() == 404
    response.close()
    TEST_WRITER.waitForTraces(1)

    then:
    def serverSpan = TEST_WRITER.flatten().find { it.getTag('component')?.toString() == 'pekko-http-server' }
    serverSpan != null
    serverSpan.getTag('http.route') == null
    serverSpan.resourceName.toString() == unmatchedSpan.resourceName.toString()

    where:
    path << ['/route-alternative/6/other', '/route-async/4/other']
  }

  def "a fallback does not retain a route rejected by a later directive"() {
    when:
    def response = client.newCall(new Request.Builder().url(address.resolve(path).toString()).build()).execute()
    assert response.code() == 200
    assert response.body().string() == 'fallback'
    response.close()
    TEST_WRITER.waitForTraces(1)

    then:
    def serverSpan = TEST_WRITER.flatten().find { it.getTag('component')?.toString() == 'pekko-http-server' }
    serverSpan.getTag('http.route') == null
    serverSpan.resourceName.toString() == 'GET'

    where:
    path << ['/directive-rejection/value', '/header-rejection/value', '/zero-consumption-rejection/value']
  }

  def "concurrent requests retain their own routes across asynchronous matching and rejection"() {
    given:
    def counter = new AtomicInteger()
    int requests = 40

    when:
    ThreadUtils.runConcurrently(8, requests, {
      int id = counter.incrementAndGet()
      boolean async = id % 2 == 0
      String path = async ? "/route-async/$id/detail/${id + 1}" : "/route-alternative/$id/accepted"
      def response = client.newCall(new Request.Builder().url(address.resolve(path).toString()).build()).execute()
      try {
        assert response.code() == 200
        assert response.body().string() == (async ? "$id:${id + 1}".toString() : id.toString())
      } finally {
        response.close()
      }
    })
    TEST_WRITER.waitForTraces(requests)

    then:
    def serverSpans = TEST_WRITER.flatten().findAll { it.getTag('component')?.toString() == 'pekko-http-server' }
    serverSpans.size() == requests
    serverSpans.every { span ->
      String route = span.getTag('url.path').toString().startsWith('/route-async/') ?
        '/route-async/{param}/detail/{param}' : '/route-alternative/{param}/accepted'
      span.getTag('http.route') == route && span.resourceName.toString() == "GET $route"
    }
  }
}

class PekkoHttpRouteOtelSemanticsForkedTest extends PekkoHttpRouteOtelSemanticsTest {
}

class PekkoHttpRouteOtelSemanticsRawResourceForkedTest extends PekkoHttpRouteOtelSemanticsTest {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TraceInstrumentationConfig.HTTP_SERVER_RAW_RESOURCE, "true")
  }
}

class PekkoHttpRouteOtelSemanticsContextSwapForkedTest extends PekkoHttpRouteOtelSemanticsTest {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TraceInstrumentationConfig.LEGACY_CONTEXT_MANAGER_ENABLED, "false")
  }
}

class PekkoHttpServerInstrumentationAsyncTest extends PekkoHttpServerInstrumentationTest {
  @Override
  HttpServer server() {
    return new PekkoHttpTestWebServer(PekkoHttpTestWebServer.BindAndHandleAsync())
  }
}

class PekkoHttpServerInstrumentationBindAndHandleTest extends PekkoHttpServerInstrumentationTest {
  @Override
  HttpServer server() {
    return new PekkoHttpTestWebServer(PekkoHttpTestWebServer.BindAndHandle())
  }

  @Override
  boolean redirectHasBody() {
    return true
  }
}

class PekkoHttpServerInstrumentationBindAndHandleContextSwapForkedTest extends PekkoHttpServerInstrumentationBindAndHandleTest {
  @Override
  boolean recreateServerForEachTest() {
    // This forked suite changes a process-wide setting; do not let its actor system outlive it.
    true
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TraceInstrumentationConfig.LEGACY_CONTEXT_MANAGER_ENABLED, "false")
  }
}

class PekkoHttpServerInstrumentationBindAndHandleAsyncWithRouteAsyncHandlerTest extends PekkoHttpServerInstrumentationTest {
  @Override
  HttpServer server() {
    return new PekkoHttpTestWebServer(PekkoHttpTestWebServer.BindAndHandleAsyncWithRouteAsyncHandler())
  }

  @Override
  boolean redirectHasBody() {
    return true
  }
}

class PekkoHttpServerInstrumentationAsyncHttp2Test extends PekkoHttpServerInstrumentationTest {
  @Override
  HttpServer server() {
    return new PekkoHttpTestWebServer(PekkoHttpTestWebServer.BindAndHandleAsyncHttp2())
  }
}


abstract class PekkoHttpRouteConfigurationTest extends WithHttpServer<PekkoHttpTestWebServer> implements TestingGenericHttpNamingConventions.ServerV0 {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TraceInstrumentationConfig.LEGACY_CONTEXT_MANAGER_ENABLED, "true")
  }

  @Override
  HttpServer server() {
    new PekkoHttpTestWebServer(PekkoHttpTestWebServer.BindAndHandle())
  }

  boolean routeEnabled() {
    false
  }
  boolean otelEnabled() {
    false
  }

  boolean routeBasedNaming() {
    true
  }

  def "route opt in respects naming semantics"() {
    when:
    def response = client.newCall(new Request.Builder().url(address.resolve('/items/value').toString()).build()).execute()
    assert response.code() == 200
    response.close()
    TEST_WRITER.waitForTraces(1)

    then:
    def span = TEST_WRITER.flatten().find { it.getTag('component')?.toString() == 'pekko-http-server' }
    span.getTag('http.route') == (routeEnabled() ? '/items/{param}' : null)
    span.resourceName.toString() == (otelEnabled() ? 'GET' : (routeBasedNaming() ? 'GET /items/{param}' : 'GET /items/value'))
  }

  def "rejected route leaves the original resource"() {
    when:
    def response = client.newCall(new Request.Builder().url(address.resolve(path).toString()).build()).execute()
    assert response.code() == 200
    assert response.body().string() == 'fallback'
    response.close()
    TEST_WRITER.waitForTraces(1)

    then:
    def span = TEST_WRITER.flatten().find { it.getTag('component')?.toString() == 'pekko-http-server' }
    span.getTag('http.route') == null
    span.resourceName.toString() == (otelEnabled() ? 'GET' : "GET $path")

    where:
    path << ['/header-rejection/value', '/zero-consumption-rejection/value']
  }
}

class PekkoHttpRouteEnabledForkedTest extends PekkoHttpRouteConfigurationTest {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig('integration.pekko-http-route.enabled', 'true')
  }

  @Override
  boolean routeEnabled() {
    true
  }
}

class PekkoHttpRouteDisabledOtelSemanticsForkedTest extends PekkoHttpRouteConfigurationTest implements TestingOtelHttpSemantics {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig('integration.pekko-http-route.enabled', 'false')
  }

  @Override
  boolean otelEnabled() {
    true
  }
}

class PekkoHttpRouteNamingDisabledForkedTest extends PekkoHttpRouteEnabledForkedTest {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig('http.server.route-based-naming', 'false')
  }

  @Override
  boolean routeBasedNaming() {
    false
  }
}

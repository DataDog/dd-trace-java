import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.base.WithHttpServer
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.agent.test.naming.TestingOtelHttpSemantics
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.test.util.ThreadUtils
import datadog.trace.instrumentation.akkahttp.AkkaHttpServerDecorator
import okhttp3.HttpUrl
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import spock.lang.IgnoreIf
import spock.lang.Shared

import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_JSON
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_MULTIPART
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

abstract class AkkaHttpServerInstrumentationTest extends HttpServerTest<AkkaHttpTestWebServer> {

  @Override
  String component() {
    return AkkaHttpServerDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    return "akka-http.request"
  }

  @Override
  boolean testExceptionBody() {
    false
  }

  @Override
  boolean hasExtraErrorInformation() {
    return true
  }

  @Override
  protected boolean enabledFinishTimingChecks() {
    true
  }

  @Override
  boolean changesAll404s() {
    true
  }

  @Override
  boolean testBlocking() {
    true
  }

  @Override
  boolean testBlockingOnResponse() {
    true
  }

  @Override
  boolean testRequestBody() {
    true
  }

  @Override
  boolean testBodyUrlencoded() {
    true
  }

  @Override
  boolean testBodyMultipart() {
    true
  }

  @Override
  boolean testBodyFilenames() {
    true
  }

  @Override
  boolean testBodyFilesContent() {
    true
  }

  @Override
  boolean testBodyJson() {
    true
  }

  @Override
  boolean isRequestBodyNoStreaming() {
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

  def "propagate trace id when we ping akka-http concurrently"() {
    expect:
    ThreadUtils.runConcurrently(10, totalInvocations, {
      def id = counter.incrementAndGet()
      doAndValidateRequest(id)
    })

    and:
    TEST_WRITER.waitForTraces(totalInvocations)
  }

  @IgnoreIf({ !instance.testBodyMultipart() })
  def 'test instrumentation gateway multipart request body — strict variant'() {
    setup:
    def body = new MultipartBody.Builder()
      .setType(MultipartBody.FORM)
      .addFormDataPart('a', 'x')
      .build()

    def url = HttpUrl.get(BODY_MULTIPART.resolve(address)).newBuilder()
      .encodedQuery('variant=strictUnmarshaller')
      .build()
    def request = new Request.Builder()
      .url(url)
      .method('POST', body)
      .build()
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    expect:
    response.body().charStream().text == '[a:[x]]'

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    TEST_WRITER.get(0).any {
      it.getTag('request.body.converted') == '[a:[x]]'
    }
  }

  @IgnoreIf({ !instance.testBodyJson()})
  def 'test instrumentation gateway json request body — spray variant'() {
    setup:
    def url = HttpUrl.get(BODY_JSON.resolve(address)).newBuilder()
      .encodedQuery('variant=spray')
      .build()
    def request = new Request.Builder()
      .url(url)
      .method('POST', RequestBody.create(okhttp3.MediaType.get('application/json'), '{"a":"x"}\n'))
      .build()
    def response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    expect:
    response.body().charStream().text == '{"a":"x"}'

    when:
    TEST_WRITER.waitForTraces(1)

    then:
    TEST_WRITER.get(0).any {
      it.getTag('request.body.converted') == '[a:[x]]'
    }
  }

  void 'content length and type are provided to IG on strict responses'() {
    setup:
    Request request = request(SUCCESS, 'GET', null)
      .header(IG_EXTRA_SPAN_NAME_HEADER, 'ig-span')
      .header(IG_ASK_FOR_RESPONSE_HEADER_TAGS_HEADER, 'true')
      .build()
    Response response = client.newCall(request).execute()
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }

    expect:
    response.body().charStream().text == SUCCESS.body

    when:
    TEST_WRITER.waitForTraces(1)
    def tags = TEST_WRITER.get(0).find { it.spanName == 'ig-span' }.tags

    then:
    tags['response.header.content-type'] != null
    tags['response.header.content-length'] == SUCCESS.body.length() as String
  }
}

abstract class AkkaHttpServerInstrumentationSyncTest extends AkkaHttpServerInstrumentationTest {
  @Override
  HttpServer server() {
    return new AkkaHttpTestWebServer(AkkaHttpTestWebServer.BindAndHandleSync())
  }

  @Override
  String expectedOperationName() {
    return operation()
  }

  // we test body endpoints only on the async tests
  @Override
  boolean testRequestBody() {
    false
  }

  @Override
  boolean testBodyMultipart() {
    false
  }

  @Override
  boolean testBodyFilenames() {
    false
  }

  @Override
  boolean testBodyFilesContent() {
    false
  }

  @Override
  boolean testBodyJson() {
    false
  }

  @Override
  boolean testBodyUrlencoded() {
    false
  }
}

class AkkaHttpServerInstrumentationSyncV0ForkedTest extends AkkaHttpServerInstrumentationSyncTest {
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
    return "akka-http.request"
  }
}

class AkkaHttpServerInstrumentationSyncV1ForkedTest extends AkkaHttpServerInstrumentationSyncTest implements TestingGenericHttpNamingConventions.ServerV1 {
}

class AkkaHttpServerInstrumentationSyncOtelSemanticsForkedTest extends AkkaHttpServerInstrumentationSyncV0ForkedTest implements TestingOtelHttpSemantics {
}

abstract class AkkaHttpRouteOtelSemanticsTest extends WithHttpServer<AkkaHttpTestWebServer> implements TestingGenericHttpNamingConventions.ServerV0, TestingOtelHttpSemantics {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TraceInstrumentationConfig.LEGACY_CONTEXT_MANAGER_ENABLED, "true")
  }

  @Override
  HttpServer server() {
    new AkkaHttpTestWebServer(AkkaHttpTestWebServer.BindAndHandle())
  }

  def "matched paths produce stable server routes"() {
    when:
    def response = client.newCall(new Request.Builder().url(address.resolve(path).toString()).build()).execute()
    assert response.code() == 200
    response.close()
    TEST_WRITER.waitForTraces(1)

    then:
    def serverSpan = TEST_WRITER.flatten().find { it.getTag('component')?.toString() == 'akka-http-server' }
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
    def unmatchedSpan = TEST_WRITER.flatten().find { it.getTag('component')?.toString() == 'akka-http-server' }
    TEST_WRITER.clear()
    def response = client.newCall(new Request.Builder().url(address.resolve(path).toString()).build()).execute()
    assert response.code() == 404
    response.close()
    TEST_WRITER.waitForTraces(1)

    then:
    def serverSpan = TEST_WRITER.flatten().find { it.getTag('component')?.toString() == 'akka-http-server' }
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
    def serverSpan = TEST_WRITER.flatten().find { it.getTag('component')?.toString() == 'akka-http-server' }
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
    def serverSpans = TEST_WRITER.flatten().findAll { it.getTag('component')?.toString() == 'akka-http-server' }
    serverSpans.size() == requests
    serverSpans.every { span ->
      String route = span.getTag('url.path').toString().startsWith('/route-async/') ?
        '/route-async/{param}/detail/{param}' : '/route-alternative/{param}/accepted'
      span.getTag('http.route') == route && span.resourceName.toString() == "GET $route"
    }
  }
}

class AkkaHttpRouteOtelSemanticsForkedTest extends AkkaHttpRouteOtelSemanticsTest {
}

class AkkaHttpRouteOtelSemanticsRawResourceForkedTest extends AkkaHttpRouteOtelSemanticsTest {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TraceInstrumentationConfig.HTTP_SERVER_RAW_RESOURCE, "true")
  }
}

class AkkaHttpRouteOtelSemanticsContextSwapForkedTest extends AkkaHttpRouteOtelSemanticsTest {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TraceInstrumentationConfig.LEGACY_CONTEXT_MANAGER_ENABLED, "false")
  }
}

class AkkaHttpServerInstrumentationAsyncTest extends AkkaHttpServerInstrumentationTest {
  @Override
  HttpServer server() {
    return new AkkaHttpTestWebServer(AkkaHttpTestWebServer.BindAndHandleAsync())
  }
}

class AkkaHttpServerInstrumentationBindAndHandleTest extends AkkaHttpServerInstrumentationTest {
  String akkaHttpVersion

  @Override
  HttpServer server() {
    AkkaHttpTestWebServer server = new AkkaHttpTestWebServer(AkkaHttpTestWebServer.BindAndHandle())
    akkaHttpVersion = server.system().settings().config().getString('akka.http.version')
    server
  }

  @Override
  boolean redirectHasBody() {
    return true
  }

  // StrictForm marshaller rejects with providing a Rejection
  // We can't detect the BlockingException as a consequence
  @Override
  boolean testBodyMultipart() {
    akkaHttpVersion != '10.0.10'
  }

  @Override
  boolean testBodyFilenames() {
    akkaHttpVersion != '10.0.10'
  }

  @Override
  boolean testBodyFilesContent() {
    akkaHttpVersion != '10.0.10'
  }

  @Override
  boolean testBodyUrlencoded() {
    akkaHttpVersion != '10.0.10'
  }
}

class AkkaHttpServerInstrumentationBindAndHandleContextSwapForkedTest extends AkkaHttpServerInstrumentationBindAndHandleTest {
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

class AkkaHttpServerInstrumentationBindAndHandleAsyncWithRouteAsyncHandlerTest extends AkkaHttpServerInstrumentationTest {
  String akkaHttpVersion

  @Override
  HttpServer server() {
    AkkaHttpTestWebServer server = new AkkaHttpTestWebServer(AkkaHttpTestWebServer.BindAndHandleAsyncWithRouteAsyncHandler())
    akkaHttpVersion = server.system().settings().config().getString('akka.http.version')
    server
  }

  @Override
  boolean redirectHasBody() {
    return true
  }

  @Override
  boolean testBodyMultipart() {
    akkaHttpVersion != '10.0.10'
  }

  @Override
  boolean testBodyFilenames() {
    akkaHttpVersion != '10.0.10'
  }

  @Override
  boolean testBodyFilesContent() {
    akkaHttpVersion != '10.0.10'
  }

  @Override
  boolean testBodyUrlencoded() {
    akkaHttpVersion != '10.0.10'
  }
}

class AkkaHttpServerInstrumentationAsyncHttp2Test extends AkkaHttpServerInstrumentationTest {
  @Override
  HttpServer server() {
    return new AkkaHttpTestWebServer(AkkaHttpTestWebServer.BindAndHandleAsyncHttp2())
  }
}


abstract class AkkaHttpRouteConfigurationTest extends WithHttpServer<AkkaHttpTestWebServer> implements TestingGenericHttpNamingConventions.ServerV0 {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TraceInstrumentationConfig.LEGACY_CONTEXT_MANAGER_ENABLED, "true")
  }

  @Override
  HttpServer server() {
    new AkkaHttpTestWebServer(AkkaHttpTestWebServer.BindAndHandle())
  }

  boolean routeEnabled() {
    false
  }
  boolean otelEnabled() {
    false
  }

  def "route opt in respects naming semantics"() {
    when:
    def response = client.newCall(new Request.Builder().url(address.resolve('/items/value').toString()).build()).execute()
    assert response.code() == 200
    response.close()
    TEST_WRITER.waitForTraces(1)

    then:
    def span = TEST_WRITER.flatten().find { it.getTag('component')?.toString() == 'akka-http-server' }
    span.getTag('http.route') == (routeEnabled() ? '/items/{param}' : null)
    span.resourceName.toString() == (otelEnabled() ? 'GET' : 'GET /items/value')
  }

  def "rejected route leaves the original resource"() {
    when:
    def response = client.newCall(new Request.Builder().url(address.resolve(path).toString()).build()).execute()
    assert response.code() == 200
    assert response.body().string() == 'fallback'
    response.close()
    TEST_WRITER.waitForTraces(1)

    then:
    def span = TEST_WRITER.flatten().find { it.getTag('component')?.toString() == 'akka-http-server' }
    span.getTag('http.route') == null
    span.resourceName.toString() == (otelEnabled() ? 'GET' : "GET $path")

    where:
    path << ['/header-rejection/value', '/zero-consumption-rejection/value']
  }
}

class AkkaHttpRouteEnabledForkedTest extends AkkaHttpRouteConfigurationTest {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig('integration.akka-http-route.enabled', 'true')
  }

  @Override
  boolean routeEnabled() {
    true
  }
}

class AkkaHttpRouteDisabledOtelSemanticsForkedTest extends AkkaHttpRouteConfigurationTest implements TestingOtelHttpSemantics {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig('integration.akka-http-route.enabled', 'false')
  }

  @Override
  boolean otelEnabled() {
    true
  }
}

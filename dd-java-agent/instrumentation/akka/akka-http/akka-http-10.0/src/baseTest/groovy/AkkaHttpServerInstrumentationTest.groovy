import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
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
  boolean testBodyUrlencoded() {
    akkaHttpVersion != '10.0.10'
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

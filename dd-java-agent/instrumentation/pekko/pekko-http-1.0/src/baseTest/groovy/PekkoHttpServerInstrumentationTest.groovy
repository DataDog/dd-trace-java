import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
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

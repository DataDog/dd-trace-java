import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.agent.test.utils.ThreadUtils
import datadog.trace.instrumentation.akkahttp.AkkaHttpServerDecorator
import okhttp3.Request
import spock.lang.Shared

import java.util.concurrent.atomic.AtomicInteger

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

  def "propagate trace id when we ping akka-http concurrently"() {
    expect:
    ThreadUtils.runConcurrently(10, totalInvocations, {
      def id = counter.incrementAndGet()
      doAndValidateRequest(id)
    })

    and:
    TEST_WRITER.waitForTraces(totalInvocations)
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
  @Override
  HttpServer server() {
    return new AkkaHttpTestWebServer(AkkaHttpTestWebServer.BindAndHandle())
  }

  @Override
  boolean redirectHasBody() {
    return true
  }
}

class AkkaHttpServerInstrumentationBindAndHandleAsyncWithRouteAsyncHandlerTest extends AkkaHttpServerInstrumentationTest {
  @Override
  HttpServer server() {
    return new AkkaHttpTestWebServer(AkkaHttpTestWebServer.BindAndHandleAsyncWithRouteAsyncHandler())
  }

  @Override
  boolean redirectHasBody() {
    return true
  }
}

class AkkaHttpServerInstrumentationAsyncHttp2Test extends AkkaHttpServerInstrumentationTest {
  @Override
  HttpServer server() {
    return new AkkaHttpTestWebServer(AkkaHttpTestWebServer.BindAndHandleAsyncHttp2())
  }
}

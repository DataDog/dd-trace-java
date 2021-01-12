import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.utils.ThreadUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.akkahttp.AkkaHttpServerDecorator
import okhttp3.Request
import spock.lang.Shared
import java.util.concurrent.atomic.AtomicInteger
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
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
  void stopServer(AkkaHttpTestWebServer server) {
    server.stop()
  }

  @Override
  boolean testExceptionBody() {
    false
  }

  void serverSpan(TraceAssert trace, BigInteger traceID = null, BigInteger parentID = null, String method = "GET", ServerEndpoint endpoint = SUCCESS) {
    trace.span {
      serviceName expectedServiceName()
      operationName expectedOperationName()
      resourceName endpoint.status == 404 ? "404" : "$method ${endpoint.resolve(address).path}"
      spanType DDSpanTypes.HTTP_SERVER
      errored endpoint.errored
      if (parentID != null) {
        traceId traceID
        parentId parentID
      } else {
        parent()
      }
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        "$Tags.HTTP_URL" "${endpoint.resolve(address)}"
        "$Tags.HTTP_METHOD" method
        "$Tags.HTTP_STATUS" endpoint.status
        if (endpoint.errored) {
          "error.msg" { it == null || it == EXCEPTION.body }
          "error.type" { it == null || it == Exception.name }
          "error.stack" { it == null || it instanceof String }
        }
        if (endpoint.query) {
          "$DDTags.HTTP_QUERY" endpoint.query
        }
        defaultTags(true)
      }
    }
  }

  @Shared
  def totalInvocations = 200

  @Shared
  AtomicInteger counter = new AtomicInteger(0)

  void doAndValidateRequest(int id) {
    def type = id & 1 ? "p" : "f"
    String url = address.toString() + "injected-id/${type}ing/$id"
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

class AkkaHttpServerInstrumentationTestSync extends AkkaHttpServerInstrumentationTest {
  @Override
  AkkaHttpTestWebServer startServer(int port) {
    return new AkkaHttpTestWebServer(port, AkkaHttpTestWebServer.BindAndHandleSync())
  }
}

class AkkaHttpServerInstrumentationTestAsync extends AkkaHttpServerInstrumentationTest {
  @Override
  AkkaHttpTestWebServer startServer(int port) {
    return new AkkaHttpTestWebServer(port, AkkaHttpTestWebServer.BindAndHandleAsync())
  }
}

class AkkaHttpServerInstrumentationTestBindAndHandle extends AkkaHttpServerInstrumentationTest {
  @Override
  AkkaHttpTestWebServer startServer(int port) {
    return new AkkaHttpTestWebServer(port, AkkaHttpTestWebServer.BindAndHandle())
  }

  @Override
  boolean redirectHasBody() {
    return true
  }
}

class AkkaHttpServerInstrumentationTestBindAndHandleAsyncWithRouteAsyncHandler extends AkkaHttpServerInstrumentationTest {
  @Override
  AkkaHttpTestWebServer startServer(int port) {
    return new AkkaHttpTestWebServer(port, AkkaHttpTestWebServer.BindAndHandleAsyncWithRouteAsyncHandler())
  }

  @Override
  boolean redirectHasBody() {
    return true
  }
}

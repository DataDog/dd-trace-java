import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.utils.ThreadUtils
import datadog.trace.instrumentation.akkahttp.AkkaHttpServerDecorator
import okhttp3.Request
import spock.lang.Shared

import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.api.Checkpointer.CPU
import static datadog.trace.api.Checkpointer.END
import static datadog.trace.api.Checkpointer.SPAN
import static datadog.trace.api.Checkpointer.THREAD_MIGRATION

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

  def "checkpoints balance"() {
    when:
    ThreadUtils.runConcurrently(10, totalInvocations, {
      def id = counter.incrementAndGet()
      doAndValidateRequest(id)
    })
    TEST_WRITER.waitForTraces(totalInvocations)
    then:
    totalInvocations * TEST_CHECKPOINTER.checkpoint(_, SPAN)
    totalInvocations * TEST_CHECKPOINTER.checkpoint(_, SPAN | END)
    _ * TEST_CHECKPOINTER.checkpoint(_, THREAD_MIGRATION)
    _ * TEST_CHECKPOINTER.checkpoint(_, THREAD_MIGRATION | END)
    _ * TEST_CHECKPOINTER.checkpoint(_, CPU | END)
    _ * TEST_CHECKPOINTER.onRootSpan(_, _)
    0 * TEST_CHECKPOINTER._
  }
}

class AkkaHttpServerInstrumentationSyncTest extends AkkaHttpServerInstrumentationTest {
  @Override
  HttpServer server() {
    return new AkkaHttpTestWebServer(AkkaHttpTestWebServer.BindAndHandleSync())
  }
}

class AkkaHttpServerInstrumentationAsyncTest extends AkkaHttpServerInstrumentationTest {
  @Override
  HttpServer server() {
    return new AkkaHttpTestWebServer(AkkaHttpTestWebServer.BindAndHandleAsync())
  }

  @Override
  def setup() {
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS)
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

  @Override
  def setup() {
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS)
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

  @Override
  def setup() {
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS)
  }
}

class AkkaHttpServerInstrumentationAsyncHttp2Test extends AkkaHttpServerInstrumentationTest {
  @Override
  HttpServer server() {
    return new AkkaHttpTestWebServer(AkkaHttpTestWebServer.BindAndHandleAsyncHttp2())
  }

  @Override
  def setup() {
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS)
  }
}

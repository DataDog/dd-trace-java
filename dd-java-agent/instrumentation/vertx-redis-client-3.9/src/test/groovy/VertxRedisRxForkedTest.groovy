import datadog.trace.core.DDSpan
import io.vertx.reactivex.redis.client.Command
import io.vertx.reactivex.redis.client.RedisConnection
import io.vertx.reactivex.redis.client.Request
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static datadog.trace.agent.test.utils.TraceUtils.basicSpan

class VertxRedisRxForkedTest extends VertxRedisTestBase {

  @Shared
  @AutoCleanup
  RedisConnection redisConnection

  def setupSpec() {
    def latch = new CountDownLatch(1)
    redis.connect({ar ->
      try {
        redisConnection = new RedisConnection(ar.result())
      } catch (Throwable t) {
        t.printStackTrace(System.out)
      } finally {
        latch.countDown()
      }
    })
    latch.await(10, TimeUnit.SECONDS)
    assert redisConnection
  }

  def "set and get command"() {
    when:
    def set = runWithParentAndHandler(Request.cmd(Command.SET).arg("foo").arg("bar"))
    def get = runWithParentAndHandler(Request.cmd(Command.GET).arg("foo"))

    then:
    set == "OK"
    get == "bar"
    assertTraces(2) {
      parentTraceWithCommandAndHandler(it, "SET")
      parentTraceWithCommandAndHandler(it, "GET")
    }
  }

  def "set and get command without parent"() {
    when:
    def set = runWithHandler(Request.cmd(Command.SET).arg("foo").arg("bar"))
    def get = runWithHandler(Request.cmd(Command.GET).arg("foo"))

    then:
    set == "OK"
    get == "bar"
    assertTraces(4) {
      trace(1) {
        redisSpan(it, "SET")
      }
      trace(1) {
        basicSpan(it, "handler")
      }
      trace(1) {
        redisSpan(it, "GET")
      }
      trace(1) {
        basicSpan(it, "handler")
      }
    }
  }

  String runWithHandler(final Request request) {
    redisConnection.rxSend(request).map({ r ->
      runUnderTrace("handler") {
        responseToString(r.delegate)
      }
    }).toObservable().blockingFirst()
  }

  String runWithParentAndHandler(final Request request) {
    String result = null
    def parentSpan = runUnderTrace("parent") {
      result = runWithHandler(request)
      blockUntilChildSpansFinished(1)
      activeSpan() as DDSpan
    }
    TEST_WRITER.waitUntilReported(parentSpan)
    result
  }
}

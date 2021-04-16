import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import io.vertx.core.AsyncResult
import io.vertx.core.Handler
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.redis.client.Command
import io.vertx.redis.client.Redis
import io.vertx.redis.client.Request
import io.vertx.redis.client.Response
import redis.embedded.RedisServer
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.util.function.Function

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

abstract class VertxRedisTestBase extends AgentTestRunner {

  @Shared
  int port = PortUtils.randomOpenPort()

  @AutoCleanup(value = "stop")
  @Shared
  RedisServer redisServer = RedisServer
  .builder()
  // bind to localhost to avoid firewall popup
  .setting("bind 127.0.0.1")
  // set max memory to avoid problems in CI
  .setting("maxmemory 128M")
  .port(port)
  .build()

  @AutoCleanup
  @Shared
  def vertx = Vertx.vertx(new VertxOptions())

  @AutoCleanup
  @Shared
  Redis redis = null

  def setupSpec() {
    println "Using redis: $redisServer.args"
    redisServer.start()
    redis = Redis.createClient(vertx, "redis://127.0.0.1:$port")
  }

  def setup() {
    cleanUpTraces()
  }

  void cleanUpTraces() {
    def cleanupSpan = runUnderTrace("cleanup") {
      redis.send(Request.cmd(Command.FLUSHALL), Promise.promise())
      blockUntilChildSpansFinished(1)
      activeSpan() as DDSpan
    }
    TEST_WRITER.waitUntilReported(cleanupSpan)
    TEST_WRITER.start()
  }

  public <T, R> R runWithParentAndHandler(final Handler<Handler<AsyncResult<T>>> redisCommand,
    final Function< T, R> resultFunction = null) {
    R result = null
    def parentSpan = runUnderTrace("parent") {
      redisCommand.handle({ ar ->
        runUnderTrace("handler") {
          if (resultFunction) {
            result = resultFunction.apply(ar.result())
          }
        }
      })
      blockUntilChildSpansFinished(2)
      activeSpan() as DDSpan
    }
    TEST_WRITER.waitUntilReported(parentSpan)
    result
  }

  static void parentTraceWithCommandAndHandler(ListWriterAssert lw, String command) {
    lw.trace(3, true) {
      basicSpan(it, "handler", span(2))
      basicSpan(it,"parent")
      redisSpan(it, command, span(1)) // name is redis.query
    }
  }

  static void redisSpan(TraceAssert trace, String command, DDSpan parentSpan = null) {
    trace.span {
      if (parentSpan) {
        childOf parentSpan
      }
      serviceName "redis"
      operationName "redis.query"
      resourceName command
      spanType DDSpanTypes.REDIS
      topLevel parentSpan ? true : false
      tags {
        "$Tags.COMPONENT" "redis-command"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.DB_TYPE" "redis"
        defaultTags()
      }
    }
  }

  String responseToString(Response r) {
    r.toString()
  }

  Integer responseToInteger(Response r) {
    r.toInteger()
  }

  List<String> responseToStrings(Response r) {
    r.iterator().collect {it.toString() }
  }

  public <T> T identity(T t) {
    return t
  }
}

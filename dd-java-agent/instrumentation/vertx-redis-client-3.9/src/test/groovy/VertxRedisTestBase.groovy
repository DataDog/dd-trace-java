import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

import com.redis.testcontainers.RedisContainer
import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.naming.VersionedNamingTestBase
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
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Function

abstract class VertxRedisTestBase extends VersionedNamingTestBase {

  @Shared
  int port

  @AutoCleanup(value = "stop")
  @Shared
  def redisServer = new RedisContainer(RedisContainer.DEFAULT_IMAGE_NAME.withTag(RedisContainer.DEFAULT_TAG))

  @Shared
  @AutoCleanup(quiet = true)
  Redis redis = null

  @AutoCleanup
  @Shared
  def vertx = Vertx.vertx(new VertxOptions())

  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return "redis"
  }

  @Override
  String operation() {
    return "redis.query"
  }

  @Override
  boolean useStrictTraceWrites() {
    false
  }

  def setupSpec() {
    redisServer.start()
    redis = Redis.createClient(vertx, redisServer.getRedisURI())
    port = URI.create(redisServer.getRedisURI()).getPort()
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

  public <T, R> R runWithHandler(final Handler<Handler<AsyncResult<T>>> redisCommand,
    final Function<T, R> resultFunction = null) {
    R result = null
    CountDownLatch latch = new CountDownLatch(1)
    redisCommand.handle({ ar ->
      runUnderTrace("handler") {
        if (resultFunction) {
          result = resultFunction.apply(ar.result())
        }
      }
      latch.countDown()
    })
    assert latch.await(10, TimeUnit.SECONDS)
    result
  }

  public <T, R> R runWithParentAndHandler(final Handler<Handler<AsyncResult<T>>> redisCommand,
    final Function<T, R> resultFunction = null) {
    R result = null
    def parentSpan = runUnderTrace("parent") {
      result = runWithHandler(redisCommand, resultFunction)
      blockUntilChildSpansFinished(2)
      activeSpan() as DDSpan
    }
    TEST_WRITER.waitUntilReported(parentSpan)
    result
  }

  void parentTraceWithCommandAndHandler(ListWriterAssert lw, String command) {
    lw.trace(3, true) {
      basicSpan(it, "handler", span(1))
      basicSpan(it, "parent")
      redisSpan(it, command, span(1)) // name is redis.query
    }
  }

  void redisSpan(TraceAssert trace, String command, DDSpan parentSpan = null) {
    trace.span {
      if (parentSpan) {
        childOf parentSpan
      }
      serviceName service()
      operationName operation()
      resourceName command
      spanType DDSpanTypes.REDIS
      measured true
      tags {
        "$Tags.COMPONENT" "redis-command"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.DB_TYPE" "redis"
        "$Tags.PEER_PORT" port
        "$Tags.PEER_HOSTNAME" { it == "127.0.0.1" || it == "localhost" }
        peerServiceFrom(Tags.PEER_HOSTNAME)
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
    r.iterator().collect { it.toString() }
  }

  public <T> T identity(T t) {
    return t
  }
}

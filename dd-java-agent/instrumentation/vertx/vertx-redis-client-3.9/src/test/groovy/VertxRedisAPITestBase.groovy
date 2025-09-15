import datadog.trace.test.util.Flaky
import io.vertx.core.AsyncResult
import io.vertx.core.Handler
import io.vertx.redis.client.RedisAPI
import io.vertx.redis.client.Response
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan

abstract class VertxRedisAPITestBase extends VertxRedisTestBase {

  @AutoCleanup(quiet = true)
  @Shared
  RedisAPI redisAPI

  def setupSpec() {
    redisAPI = createRedis()
  }

  abstract RedisAPI createRedis()

  def "dbsize (1 arg)"() {
    when:
    def dbsize = runWithParentAndHandler({ Handler<AsyncResult<Response>> h ->
      redisAPI.dbsize(h)
    }, this.&responseToInteger)

    then:
    dbsize == 0
    assertTraces(1) {
      parentTraceWithCommandAndHandler(it, "DBSIZE")
    }
  }

  def "set (2 args)"() {
    when:
    def set = runWithParentAndHandler({ Handler<AsyncResult<Response>> h ->
      redisAPI.set(["foo", "bar"], h)
    }, this.&responseToString)

    then:
    set == "OK"
    assertTraces(1) {
      parentTraceWithCommandAndHandler(it, "SET")
    }
  }

  def "get (2 args)"() {
    when:
    def set = runWithParentAndHandler({ Handler<AsyncResult<Response>> h ->
      redisAPI.set(["foo", "baz"], h)
    }, this.&responseToString)
    def get = runWithParentAndHandler({ Handler<AsyncResult<Response>> h ->
      redisAPI.get("foo", h)
    }, this.&responseToString)

    then:
    set == "OK"
    get == "baz"
    assertTraces(2) {
      parentTraceWithCommandAndHandler(it, "SET")
      parentTraceWithCommandAndHandler(it, "GET")
    }
  }

  def "decrby (3 args)"() {
    when:
    def set = runWithParentAndHandler({ Handler<AsyncResult<Response>> h ->
      redisAPI.set(["foo", "17"], h)
    }, this.&responseToString)
    def decrby = runWithParentAndHandler({ Handler<AsyncResult<Response>> h ->
      redisAPI.decrby("foo", "29", h)
    }, this.&responseToInteger)


    then:
    set == "OK"
    decrby == -12
    assertTraces(2) {
      parentTraceWithCommandAndHandler(it, "SET")
      parentTraceWithCommandAndHandler(it, "DECRBY")
    }
  }

  def "setbit (4 args)"() {
    when:
    def res = runWithParentAndHandler({ Handler<AsyncResult<Response>> h ->
      redisAPI.setbit("foo", "0", "1", h)
    }, this.&responseToInteger)

    then:
    res == 0
    assertTraces(1) {
      parentTraceWithCommandAndHandler(it, "SETBIT")
    }
  }

  @Flaky("https://github.com/DataDog/dd-trace-java/issues/6910")
  def "linsert (5 args)"() {
    when:
    def rpush = runWithParentAndHandler({ Handler<AsyncResult<Response>> h ->
      redisAPI.rpush(["foo", "Hello", "World"], h)
    }, this.&responseToInteger)
    def linsert = runWithParentAndHandler({ Handler<AsyncResult<Response>> h ->
      redisAPI.linsert("foo", "BEFORE", "World", "There", h)
    }, this.&responseToInteger)
    def lrange = runWithParentAndHandler({ Handler<AsyncResult<Response>> h ->
      redisAPI.lrange("foo", "0", "-1", h)
    }, this.&responseToStrings)

    then:
    rpush == 2
    linsert == 3
    lrange == ["Hello", "There", "World"]
    assertTraces(3) {
      parentTraceWithCommandAndHandler(it, "RPUSH")
      parentTraceWithCommandAndHandler(it, "LINSERT")
      parentTraceWithCommandAndHandler(it, "LRANGE")
    }
  }

  def "dbsize without parent (1 arg)"() {
    when:
    def dbsize = runWithHandler({ Handler<AsyncResult<Response>> h ->
      redisAPI.dbsize(h)
    }, this.&responseToInteger)

    then:
    dbsize == 0
    assertTraces(2) {
      trace(1) {
        redisSpan(it, "DBSIZE")
      }
      trace(1) {
        basicSpan(it, "handler")
      }
    }
  }
}

class VertxRedisAPIRedisForkedTest extends VertxRedisAPITestBase {

  @Override
  RedisAPI createRedis() {
    return RedisAPI.api(redis)
  }
}

class VertxRedisAPIRedisConnectionForkedTest extends VertxRedisAPITestBase {
  @Override
  RedisAPI createRedis() {
    RedisAPI api = null

    new PollingConditions(delay: 3, timeout: 15).eventually {
      (api = connect()) != null
    }

    return api
  }

  private RedisAPI connect() {
    def latch = new CountDownLatch(1)
    RedisAPI api = null
    redis.connect({ ar ->
      try {
        if (ar.succeeded()) {
          api = RedisAPI.api(ar.result())
        } else {
          println "Redis connection failed"
          ar.cause().printStackTrace(System.out)
        }
      } catch (Throwable t) {
        t.printStackTrace(System.out)
      } finally {
        latch.countDown()
      }
    })
    latch.await(10, TimeUnit.SECONDS)

    return api
  }
}

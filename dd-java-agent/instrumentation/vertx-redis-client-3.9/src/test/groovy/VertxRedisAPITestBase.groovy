import io.vertx.core.AsyncResult
import io.vertx.core.Handler
import io.vertx.redis.client.RedisAPI
import io.vertx.redis.client.Response
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

abstract class VertxRedisAPITestBase extends VertxRedisTestBase {

  @AutoCleanup
  @Shared
  RedisAPI redisAPI = null

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
}

class VertxRedisAPIRedisForkedTest extends VertxRedisAPITestBase {
  def setupSpec() {
    redisAPI = RedisAPI.api(redis)
  }
}

class VertxRedisAPIRedisConnectionForkedTest extends VertxRedisAPITestBase {
  def setupSpec() {
    def latch = new CountDownLatch(1)
    redis.connect({ar ->
      try {
        redisAPI = RedisAPI.api(ar.result())
      } catch (Throwable t) {
        t.printStackTrace(System.out)
      } finally {
        latch.countDown()
      }
    })
    latch.await(10, TimeUnit.SECONDS)
    assert redisAPI
  }
}

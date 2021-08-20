import io.vertx.core.AsyncResult
import io.vertx.core.Handler
import io.vertx.redis.RedisClient
import io.vertx.redis.RedisOptions
import spock.lang.AutoCleanup
import spock.lang.Shared

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan

class VertxRedisClientForkedTest extends VertxRedisTestBase {

  @AutoCleanup
  @Shared
  RedisClient redisClient = null

  def setupSpec() {
    redisClient = RedisClient.create(vertx, new RedisOptions().setHost("127.0.0.1").setPort(port))
  }

  def "set and get command"() {
    when:
    runWithParentAndHandler({ Handler<AsyncResult<Void>> h ->
      redisClient.set("foo", "baz", h)
    })
    def get = runWithParentAndHandler({ Handler<AsyncResult<String>> h ->
      redisClient.get("foo", h)
    }, this.&identity)

    then:
    get == "baz"
    assertTraces(2) {
      parentTraceWithCommandAndHandler(it, "SET")
      parentTraceWithCommandAndHandler(it, "GET")
    }
  }

  def "set and get command without parent"() {
    when:
    runWithHandler({ Handler<AsyncResult<Void>> h ->
      redisClient.set("foo", "baz", h)
    })
    def get = runWithHandler({ Handler<AsyncResult<String>> h ->
      redisClient.get("foo", h)
    }, this.&identity)

    then:
    get == "baz"
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
}

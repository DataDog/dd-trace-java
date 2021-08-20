import io.vertx.core.AsyncResult
import io.vertx.core.Handler
import io.vertx.redis.client.Command
import io.vertx.redis.client.Request
import io.vertx.redis.client.Response

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan

class VertxRedisForkedTest extends VertxRedisTestBase {

  def "set and get command"() {
    when:
    def set = runWithParentAndHandler({ Handler<AsyncResult<Response>> h ->
      redis.send(Request.cmd(Command.SET).arg("foo").arg("bar"), h)
    }, this.&responseToString)
    def get = runWithParentAndHandler({ Handler<AsyncResult<Response>> h ->
      redis.send(Request.cmd(Command.GET).arg("foo"), h)
    }, this.&responseToString)

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
    def set = runWithHandler({ Handler<AsyncResult<Response>> h ->
      redis.send(Request.cmd(Command.SET).arg("foo").arg("bar"), h)
    }, this.&responseToString)
    def get = runWithHandler({ Handler<AsyncResult<Response>> h ->
      redis.send(Request.cmd(Command.GET).arg("foo"), h)
    }, this.&responseToString)

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

  def "work with reused request"() {
    setup:
    def request = Request.cmd(Command.SET).arg("foo").arg("bar")

    when:
    def set1 = runWithParentAndHandler({ Handler<AsyncResult<Response>> h ->
      redis.send(request, h)
    }, this.&responseToString)

    then:
    assert set1 == "OK"

    when:
    def set2 = runWithParentAndHandler({ Handler<AsyncResult<Response>> h ->
      redis.send(request, h)
    }, this.&responseToString)

    then:
    assert set2 == "OK"
    assertTraces(2) {
      parentTraceWithCommandAndHandler(it, "SET")
      parentTraceWithCommandAndHandler(it, "SET")
    }
  }
}

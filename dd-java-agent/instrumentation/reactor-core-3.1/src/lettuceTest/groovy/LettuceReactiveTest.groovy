import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.TraceUtils
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.reactive.RedisStringReactiveCommands
import reactor.core.scheduler.Schedulers

class LettuceReactiveTest extends AgentTestRunner {

  RedisStringReactiveCommands<String, String> reactive

  def setup() {
    RedisClient client = RedisClient.create("redis://localhost:6379")
    StatefulRedisConnection<String, String> connection = client.connect()
    reactive = connection.reactive()
  }

  def "blocking subscriber"() {
    when:
    TraceUtils.runUnderTrace("test-parent") {
      reactive.set("a", "1")
        .then(reactive.get("a")) // The get here is getting ending up in another trace
        .block()
    }
    TEST_WRITER.waitForTraces(2)

    def traces = TEST_WRITER.collect()
    traces.removeAll {
      it[0].resourceName.startsWith("CONNECT")
    }

    then:
    traces.size() == 1
    traces.get(0).size() == 3
  }

  def "async subscriber"() {
    when:
    TraceUtils.runUnderTrace("test-parent") {
      reactive.set("a", "1")
        .then(reactive.get("a")) // The get here is getting ending up in another trace
        .subscribe()
    }
    TEST_WRITER.waitForTraces(2)

    def traces = TEST_WRITER.collect()
    traces.removeAll {
      it[0].resourceName.startsWith("CONNECT")
    }

    then:
    traces.size() == 1
    traces.get(0).size() == 3
  }

  def "async subscriber with specific thread pool"() {
    when:
    TraceUtils.runUnderTrace("test-parent") {
      reactive.set("a", "1")
        .then(reactive.get("a")) // The get here is getting ending up in another trace
        .subscribeOn(Schedulers.elastic())
        .subscribe()
    }
    TEST_WRITER.waitForTraces(2)

    def traces = TEST_WRITER.collect()
    traces.removeAll {
      it[0].resourceName.startsWith("CONNECT")
    }

    then:
    traces.size() == 1
    traces.get(0).size() == 3
  }
}

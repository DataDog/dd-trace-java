import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.TraceUtils
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.reactive.RedisStringReactiveCommands
import org.testcontainers.containers.GenericContainer
import spock.lang.Shared

class LettuceReactiveTest extends AgentTestRunner {
  @Shared
  GenericContainer redis = new GenericContainer<>("redis:5.0.3-alpine").withExposedPorts(6379)

  RedisStringReactiveCommands<String, String> reactive

  def setup() {
    redis.start()
    RedisClient client = RedisClient.create("redis://localhost:" + redis.getMappedPort(6379))
    StatefulRedisConnection<String, String> connection = client.connect()
    reactive = connection.reactive()
  }

  def "test"() {
    when:
    TraceUtils.runUnderTrace("test-parent") {
      reactive.set("a", "1")
        .then(reactive.get("a")) // The get here is getting ending up in another trace
        .block()
    }
    TEST_WRITER.waitForTraces(2)

    def traces = TEST_WRITER.collect()

    then:
    traces.removeAll {
      it[0].resourceName.startsWith("CONNECT")
    }
    traces.toArray().length == 1
  }
}

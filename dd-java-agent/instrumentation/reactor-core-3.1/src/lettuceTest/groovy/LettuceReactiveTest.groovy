import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.agent.test.utils.TraceUtils
import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulConnection
import io.lettuce.core.api.reactive.RedisReactiveCommands
import reactor.core.scheduler.Schedulers
import redis.embedded.RedisServer
import spock.lang.Shared

class LettuceReactiveTest extends AgentTestRunner {
  public static final String HOST = "127.0.0.1"
  public static final int DB_INDEX = 0
  // Disable autoreconnect so we do not get stray traces popping up on server shutdown
  public static final ClientOptions CLIENT_OPTIONS = ClientOptions.builder().autoReconnect(false).build()

  @Shared
  String embeddedDbUri

  @Shared
  RedisServer redisServer

  RedisClient redisClient
  StatefulConnection connection
  RedisReactiveCommands<String, ?> reactive

  def setupSpec() {
    int port = PortUtils.randomOpenPort()
    String dbAddr = HOST + ":" + port + "/" + DB_INDEX
    embeddedDbUri = "redis://" + dbAddr

    redisServer = RedisServer.builder()
    // bind to localhost to avoid firewall popup
      .setting("bind " + HOST)
    // set max memory to avoid problems in CI
      .setting("maxmemory 128M")
      .port(port).build()
  }

  def setup() {
    redisClient = RedisClient.create(embeddedDbUri)

    println "Using redis: $redisServer.args"
    redisServer.start()
    redisClient.setOptions(CLIENT_OPTIONS)
    connection = redisClient.connect()

    reactive = connection.reactive()
    reactive.set("test", "test").block()

    TEST_WRITER.waitForTraces(2)
    TEST_WRITER.clear()
  }

  def cleanup() {
    connection.close()
    redisServer.stop()
  }

  def "blocking subscriber"() {
    when:
    TraceUtils.runUnderTrace("test-parent") {
      reactive.set("a", "1")
        .then(reactive.get("a")) // The get here is ending up in another trace
        .block()
    }
    TEST_WRITER.waitForTraces(1)

    def traces = TEST_WRITER.collect()

    then:
    traces.size() == 1
    traces.get(0).size() == 3
  }

  def "async subscriber"() {
    when:
    TraceUtils.runUnderTrace("test-parent") {
      reactive.set("a", "1")
        .then(reactive.get("a")) // The get here is ending up in another trace
        .subscribe()
    }
    TEST_WRITER.waitForTraces(1)

    def traces = TEST_WRITER.collect()

    then:
    traces.size() == 1
    traces.get(0).size() == 3
  }

  def "async subscriber with specific thread pool"() {
    when:
    TraceUtils.runUnderTrace("test-parent") {
      reactive.set("a", "1")
        .then(reactive.get("a")) // The get here is ending up in another trace
        .subscribeOn(Schedulers.elastic())
        .subscribe()
    }
    TEST_WRITER.waitForTraces(1)

    def traces = TEST_WRITER.collect()

    then:
    traces.size() == 1
    traces.get(0).size() == 3
  }
}

import com.redis.testcontainers.RedisContainer
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.agent.test.utils.PortUtils
import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.api.reactive.RedisReactiveCommands
import io.lettuce.core.api.sync.RedisCommands
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

abstract class Lettuce5ClientTestBase extends VersionedNamingTestBase {
  public static final int DB_INDEX = 0
  // Disable autoreconnect so we do not get stray traces popping up on server shutdown
  public static final ClientOptions CLIENT_OPTIONS = ClientOptions.builder().autoReconnect(false).build()

  @Shared
  Map<String, String> testHashMap = [
    firstname: "John",
    lastname : "Doe",
    age      : "53"
  ]

  int port
  int incorrectPort
  String dbAddr
  String dbAddrNonExistent
  String dbUriNonExistent
  String embeddedDbUri

  RedisContainer redisServer = new RedisContainer(DockerImageName.parse("redis:6.2.6"))
  .waitingFor(Wait.forListeningPort())

  RedisClient redisClient
  StatefulRedisConnection connection
  RedisReactiveCommands<String, ?> reactiveCommands
  RedisAsyncCommands<String, ?> asyncCommands
  RedisCommands<String, ?> syncCommands

  @Override
  boolean useStrictTraceWrites() {
    // latest seems leaking continuations that terminates later hence the strict trace will discard our spans.
    !isLatestDepTest
  }


  def setup() {
    redisServer.start()
    println "Using redis: $redisServer.redisURI"

    port = redisServer.firstMappedPort
    incorrectPort = PortUtils.randomOpenPort()
    dbAddr = redisServer.getHost() + ":" + port + "/" + DB_INDEX
    dbAddrNonExistent = redisServer.getHost() + ":" + incorrectPort + "/" + DB_INDEX
    dbUriNonExistent = "redis://" + dbAddrNonExistent
    embeddedDbUri = "redis://" + dbAddr

    redisClient = RedisClient.create(embeddedDbUri)
    redisClient.setOptions(CLIENT_OPTIONS)

    runUnderTrace("setup") {
      new PollingConditions(delay: 3, timeout: 15).eventually {
        (connection = redisClient.connect()) != null
      }
      reactiveCommands = connection.reactive()
      asyncCommands = connection.async()
      syncCommands = connection.sync()

      syncCommands.set("TESTKEY", "TESTVAL")
      syncCommands.hmset("TESTHM", testHashMap)
    }

    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.clear()
  }

  def cleanup() {
    connection.close()

    try {
      redisClient.shutdown(5, 10, TimeUnit.SECONDS)
    } catch (Throwable ignored) {
      // No-op.
    }

    redisServer.stop()
  }
}

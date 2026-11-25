import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static java.util.concurrent.TimeUnit.SECONDS

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

  // One server and one client per spec: starting a container per test cost ~2s each, and each
  // client shutdown waits a 1s quiet period. Tests that stop the server call
  // redisServerStopsDuringTest() so cleanup() replaces both.
  @Shared
  RedisContainer redisServer = new RedisContainer(
  DockerImageName.parse(System.getProperty("test.redis.image"))
  .asCompatibleSubstituteFor("redis"))
  .waitingFor(Wait.forListeningPort())

  @Shared
  RedisClient redisClient

  StatefulRedisConnection connection
  RedisReactiveCommands<String, ?> reactiveCommands
  RedisAsyncCommands<String, ?> asyncCommands
  RedisCommands<String, ?> syncCommands

  private boolean restartRedisServer

  def setupSpec() {
    redisServer.start()
  }

  def cleanupSpec() {
    try {
      shutdownRedisClient()
    } finally {
      redisServer.stop()
    }
  }

  private void shutdownRedisClient() {
    if (redisClient != null) {
      try {
        // Lettuce 5.1 needs its executor alive to deliver pending channel-close notifications.
        redisClient.shutdownAsync(1, 10, SECONDS).get(15, SECONDS)
      } finally {
        redisClient = null
      }
    }
  }

  /** Marks the current test as stopping the shared server, so cleanup() starts a fresh one. */
  protected void redisServerStopsDuringTest() {
    restartRedisServer = true
  }

  def setup() {
    println "Using redis: $redisServer.redisURI"

    port = redisServer.firstMappedPort
    incorrectPort = PortUtils.randomOpenPort()
    dbAddr = redisServer.getHost() + ":" + port + "/" + DB_INDEX
    dbAddrNonExistent = redisServer.getHost() + ":" + incorrectPort + "/" + DB_INDEX
    dbUriNonExistent = "redis://" + dbAddrNonExistent
    embeddedDbUri = "redis://" + dbAddr

    if (redisClient == null) {
      redisClient = RedisClient.create(embeddedDbUri)
      redisClient.setOptions(CLIENT_OPTIONS)
    }

    runUnderTrace("setup") {
      new PollingConditions(delay: 3, timeout: 15).eventually {
        (connection = redisClient.connect()) != null
      }
      reactiveCommands = connection.reactive()
      asyncCommands = connection.async()
      syncCommands = connection.sync()

      // The server is shared by the spec, so drop keys written by earlier tests.
      syncCommands.flushall()
      syncCommands.set("TESTKEY", "TESTVAL")
      syncCommands.hmset("TESTHM", testHashMap)
    }

    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.clear()
  }

  def cleanup() {
    try {
      connection?.close()
    } finally {
      if (restartRedisServer) {
        // The new server gets a new mapped port, so the client must be recreated too.
        try {
          shutdownRedisClient()
        } finally {
          redisServer.stop()
          redisServer.start()
        }
      }
    }
  }
}

import com.lambdaworks.redis.ClientOptions
import com.lambdaworks.redis.RedisClient
import com.lambdaworks.redis.api.StatefulConnection
import com.lambdaworks.redis.api.async.RedisAsyncCommands
import com.lambdaworks.redis.api.sync.RedisCommands
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.agent.test.utils.PortUtils
import redis.embedded.RedisServer
import spock.lang.Shared

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

abstract class Lettuce4ClientTestBase extends VersionedNamingTestBase {
  public static final String HOST = "127.0.0.1"
  public static final int DB_INDEX = 0
  // Disable autoreconnect so we do not get stray traces popping up on server shutdown
  public static final ClientOptions CLIENT_OPTIONS = new ClientOptions.Builder().autoReconnect(false).build()

  @Shared
  int port
  @Shared
  int incorrectPort
  @Shared
  String dbAddr
  @Shared
  String dbAddrNonExistent
  @Shared
  String dbUriNonExistent
  @Shared
  String embeddedDbUri

  @Shared
  RedisServer redisServer

  @Shared
  Map<String, String> testHashMap = [
    firstname: "John",
    lastname : "Doe",
    age      : "53"
  ]

  RedisClient redisClient
  StatefulConnection connection
  RedisCommands<String, ?> syncCommands
  RedisAsyncCommands<String, ?> asyncCommands

  def setupSpec() {
    port = PortUtils.randomOpenPort()
    incorrectPort = PortUtils.randomOpenPort()
    dbAddr = HOST + ":" + port + "/" + DB_INDEX
    dbAddrNonExistent = HOST + ":" + incorrectPort + "/" + DB_INDEX
    dbUriNonExistent = "redis://" + dbAddrNonExistent
    embeddedDbUri = "redis://" + dbAddr

    redisServer = RedisServer.newRedisServer()
      // bind to localhost to avoid firewall popup
      .setting("bind " + HOST)
      // set max memory to avoid problems in CI
      .setting("maxmemory 128M")
      .port(port).build()
  }

  def setup() {
    redisServer.start()

    redisClient = RedisClient.create(embeddedDbUri)
    redisClient.setOptions(CLIENT_OPTIONS)

    runUnderTrace("setup") {
      connection = redisClient.connect()
      syncCommands = connection.sync()
      asyncCommands = connection.async()

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

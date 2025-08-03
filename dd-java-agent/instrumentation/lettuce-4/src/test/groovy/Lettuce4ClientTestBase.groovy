import com.lambdaworks.redis.ClientOptions
import com.lambdaworks.redis.RedisClient
import com.lambdaworks.redis.api.StatefulConnection
import com.lambdaworks.redis.api.async.RedisAsyncCommands
import com.lambdaworks.redis.api.sync.RedisCommands
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.agent.test.utils.PortUtils
import redis.embedded.RedisServer
import spock.lang.Shared

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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

  ThreadDumpLogger threadDumpLogger

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
    File reportDir = new File("build")
    String fullPath = reportDir.absolutePath.replace("dd-trace-java/dd-java-agent",
    "dd-trace-java/workspace/dd-java-agent")

    reportDir = new File(fullPath)
    if (!reportDir.exists()) {
      println("Folder not found: " + fullPath)
      reportDir.mkdirs()
    } else println("Folder found: " + fullPath)

    // Use the current feature name as the test name
    String testName = "${specificationContext?.currentSpec?.name ?: "unknown-spec"} : ${specificationContext?.currentFeature?.name ?: "unknown-test"}"

    threadDumpLogger = new ThreadDumpLogger(testName, reportDir)
    threadDumpLogger.start()

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
    threadDumpLogger.stop()

    connection.close()
    redisClient.shutdown()
    redisServer.stop()
  }

  // 🔒 Private helper class for thread dump logging
  private static class ThreadDumpLogger {
    private final String testName
    private final File outputDir
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()
    private ScheduledFuture<?> task

    ThreadDumpLogger(String testName, File outputDir) {
      this.testName = testName
      this.outputDir = outputDir
    }

    void start() {
      task = scheduler.scheduleAtFixedRate({
        def reportFile = new File(outputDir, "thread-dump-${System.currentTimeMillis()}.log")
        try (def writer = new FileWriter(reportFile)) {
          writer.write("=== Test: ${testName} ===\n")
          writer.write("=== Thread Dump Triggered at ${new Date()} ===\n")
          Thread.getAllStackTraces().each { thread, stack ->
            writer.write("Thread: ${thread.name}, daemon: ${thread.daemon}\n")
            stack.each { writer.write("\tat ${it}\n") }
          }
          writer.write("==============================================\n")
        }
      }, 10001, 60000, TimeUnit.MILLISECONDS)
    }

    void stop() {
      task?.cancel(false)
      scheduler.shutdownNow()
    }
  }
}

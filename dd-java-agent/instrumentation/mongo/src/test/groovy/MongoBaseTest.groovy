import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.PortUtils
import de.flapdoodle.embed.mongo.MongodExecutable
import de.flapdoodle.embed.mongo.MongodProcess
import de.flapdoodle.embed.mongo.MongodStarter
import de.flapdoodle.embed.mongo.config.IMongodConfig
import de.flapdoodle.embed.mongo.config.MongodConfigBuilder
import de.flapdoodle.embed.mongo.config.Net
import de.flapdoodle.embed.mongo.distribution.Version
import de.flapdoodle.embed.process.runtime.Network
import org.apache.commons.io.FileUtils
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Testing needs to be in a centralized project.
 */
class MongoBaseTest extends AgentTestRunner {

  @Shared
  def databaseName = "database"

  // https://github.com/flapdoodle-oss/de.flapdoodle.embed.mongo#executable-collision
  protected static final MongodStarter STARTER = MongodStarter.getDefaultInstance()

  @Shared
  int port
  @Shared
  MongodExecutable mongodExe
  @Shared
  MongodProcess mongod

  def setupSpec() throws Exception {
    // The embedded MongoDB library will fail if it starts preparing the
    // distribution and then finds that one of the files already exists.
    // Since we usually execute multiple test modules in parallel, we use
    // an exclusive file lock.
    final mongoHome = new File(FileUtils.getUserDirectory(), ".embedmongo")
    mongoHome.mkdirs()
    final lockFile = new File(mongoHome, "dd-trace-java.lock")
    final conditions = new PollingConditions(timeout: 60, delay: 0.5)
    conditions.eventually {
      final lockChannel = FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
      mongodExe = lockChannel.withCloseable {
        final lock = it.tryLock()
        assert lock != null
        try {
          // Create the MongodConfig here, including the allocation of the port.
          // If we allocate the port before acquiring the lock, some other process
          // might bind to the same port, resulting in a port conflict.
          port = PortUtils.randomOpenPort()
          final IMongodConfig mongodConfig = new MongodConfigBuilder()
            .version(Version.Main.PRODUCTION)
            .net(new Net("localhost", port, Network.localhostIsIPv6()))
            .build()

          return STARTER.prepare(mongodConfig)
        } finally {
          lock.release()
        }
      }
    }

    mongod = mongodExe.start()
  }

  def cleanupSpec() throws Exception {
    mongod?.stop()
    mongod = null
    mongodExe?.stop()
    mongodExe = null
  }

  def randomCollectionName() {
    return "testCollection-" + UUID.randomUUID()
  }

  def matchesStatement(statement) {
    return {
      assert it.replace(" ", "").replace(",\"\$db\":\"$databaseName\"", "").replace(',"lsid":{"id":"?"}', '').replace(',"readPreference":{"node":"?"}', '').replace(',"autoIndexId":"?"', '').replace(',"$readPreference":{"mode":"?"}', '') == statement
      return true
    }
  }

  def "test port open"() {
    when:
    new Socket("localhost", port)

    then:
    noExceptionThrown()
  }
}

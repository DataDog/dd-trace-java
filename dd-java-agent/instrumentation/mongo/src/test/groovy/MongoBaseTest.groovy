import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.agent.test.utils.PortUtils
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import datadog.trace.test.util.Flaky
import de.flapdoodle.embed.mongo.config.Net
import de.flapdoodle.embed.mongo.distribution.Version
import de.flapdoodle.embed.mongo.transitions.Mongod
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess
import de.flapdoodle.embed.process.io.ProcessOutput
import de.flapdoodle.embed.process.io.Processors
import de.flapdoodle.embed.process.io.Slf4jLevel
import de.flapdoodle.embed.process.runtime.Network
import de.flapdoodle.reverse.transitions.Start
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Testing needs to be in a centralized project.
 */
@Flaky("Fails sometimes with java.io.IOException https://github.com/DataDog/dd-trace-java/issues/3884")
abstract class MongoBaseTest extends VersionedNamingTestBase {
  public static final String V0_DB_TYPE = "mongo"
  public static final String V0_SERVICE = "mongo"
  public static final String V0_OPERATION = "mongo.query"
  public static final String V1_SERVICE = Config.get().getServiceName()
  public static final String V1_OPERATION = "mongodb.query"
  public static final String V1_DB_TYPE = "mongodb"


  @Shared
  def databaseName = "database"

  @Shared
  def logger = LoggerFactory.getLogger(MongoBaseTest)

  @Shared
  int port
  @Shared
  RunningMongodProcess mongodProcess

  abstract String dbType()

  def setupSpec() throws Exception {
    // The embedded MongoDB library will fail if it starts preparing the
    // distribution and then finds that one of the files already exists.
    // Since we usually execute multiple test modules in parallel, we use
    // an exclusive file lock.
    final mongoHome = new File(FileUtils.getUserDirectory(), ".embedmongo")
    mongoHome.mkdirs()

    final lockFile = new File(mongoHome, "dd-trace-java.lock")
    final conditions = new PollingConditions(timeout: 60, delay: 0.5)
    // pre download and extract
    conditions.eventually {
      final lockChannel = FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
      mongodProcess = lockChannel.withCloseable {
        final lock = it.tryLock()
        assert lock != null
        try {
          // Create the MongodConfig here, including the allocation of the port.
          // If we allocate the port before acquiring the lock, some other process
          // might bind to the same port, resulting in a port conflict.
          port = PortUtils.randomOpenPort()
          return Mongod.builder()
            .processOutput(Start.to(ProcessOutput)
            .initializedWith(ProcessOutput.builder()
            .output(Processors.logTo(logger, Slf4jLevel.INFO))
            .error(Processors.logTo(logger, Slf4jLevel.ERROR))
            .commands(Processors.named("[console>]", Processors.logTo(logger, Slf4jLevel.DEBUG)))
            .build()))
            .net(Start.to(Net).initializedWith(Net.of("localhost", port, Network.localhostIsIPv6())))
            .build()
            //force a old mongodb version to be compatible with driver version we test
            .start(Version.Main.V3_2).current()
        } finally {
          lock.release()
        }
      }
    }
  }

  def cleanupSpec() throws Exception {
    mongodProcess?.stop()
    mongodProcess = null
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

  def mongoSpan(TraceAssert trace, int index, String mongoOp, String statement, boolean renameService = false, String instance = "some-description", Object parentSpan = null, Throwable exception = null) {
    def dbType = dbType()
    trace.span(index) {
      serviceName renameService ? instance : service()
      operationName operation()
      resourceName matchesStatement(statement)
      spanType DDSpanTypes.MONGO
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
      measured true
      tags {
        "$Tags.COMPONENT" "java-mongo"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.PEER_HOSTNAME" "localhost"
        "$Tags.PEER_PORT" port
        "$Tags.DB_TYPE" dbType
        "$Tags.DB_INSTANCE" instance
        "$Tags.DB_OPERATION" mongoOp
        peerServiceFrom(Tags.DB_INSTANCE)
        defaultTags()
      }
    }
  }

  def "test port open"() {
    when:
    new Socket("localhost", port)

    then:
    noExceptionThrown()
  }
}

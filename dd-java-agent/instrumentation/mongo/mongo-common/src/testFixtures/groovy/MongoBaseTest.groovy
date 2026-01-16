import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.slf4j.LoggerFactory
import org.testcontainers.containers.MongoDBContainer
import spock.lang.Shared

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
  MongoDBContainer mongoDbContainer

  abstract String dbType()

  def mongodbImageName() {
    return "mongo:4.4.29"
  }

  def setupSpec() throws Exception {
    mongoDbContainer = new MongoDBContainer(mongodbImageName())
    mongoDbContainer.start()
    port = mongoDbContainer.getMappedPort(27017)
    logger.info("MongoDB started on port {}", port)
  }

  def cleanupSpec() throws Exception {
    mongoDbContainer.stop()
    mongoDbContainer = null
  }

  def randomCollectionName() {
    return "testCollection-" + UUID.randomUUID()
  }

  def matchesStatement(statement) {
    return {
      assert it.replace(" ", "").
      replace(",\"\$db\":\"$databaseName\"", "").
      replace(',"lsid":{"id":"?"}', '').
      replace(',"readPreference":{"node":"?"}', '').
      replace(',"autoIndexId":"?"', '').
      replace(',"$readPreference":{"mode":"?"}', '').
      replace(',"txnNumber":"?"', '').
      replace(',"$clusterTime":{"clusterTime":"?","signature":{"hash":"?","keyId":"?"}}', '') == statement
      return true
    }
  }

  def mongoSpan(TraceAssert trace, int index, String mongoOp, String statement, boolean renameService = false, String instance = "some-description", Object parentSpan = null, boolean addDbmTag = false) {
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
        "$Tags.PEER_HOSTNAME" mongoDbContainer.getHost()
        "$Tags.PEER_PORT" port
        "$Tags.DB_TYPE" dbType
        "$Tags.DB_INSTANCE" instance
        "$Tags.DB_OPERATION" mongoOp
        if (addDbmTag) {
          "$InstrumentationTags.DBM_TRACE_INJECTED" true
        }
        peerServiceFrom(Tags.DB_INSTANCE)
        defaultTags()
      }
    }
  }
}

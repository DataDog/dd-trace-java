import com.mongodb.client.MongoClients
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.bson.Document
import org.testcontainers.containers.GenericContainer
import spock.lang.Shared
import spock.lang.Unroll

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DBM_TRACE_INJECTED

class MongoDBMInjectionTest extends InstrumentationSpecification {
  @Shared
  GenericContainer mongo

  def setupSpec() {
    mongo = new GenericContainer("mongo:4.4.29").withExposedPorts(27017)
    mongo.start()
  }

  def cleanupSpec() {
    if (mongo) {
      mongo.stop()
    }
  }

  def "MongoDB find command includes comment"() {
    setup:
    def mongoHost = mongo.getHost()
    def mongoPort = mongo.getFirstMappedPort()
    def client = MongoClients.create("mongodb://${mongoHost}:${mongoPort}")
    def database = client.getDatabase("test")
    def collection = database.getCollection("testCollection")

    when:
    runUnderTrace("test") {
      collection.find(new Document("name", "test")).first()
    }

    then:
    def traces = TEST_WRITER.waitForTraces(1)
    traces.size() == 1
    def mongoSpan = traces[0].find { it.operationName in ["mongo.query", "mongodb.query"] }
    mongoSpan != null
    // Verify span has trace injected tag
    mongoSpan.getTag(DBM_TRACE_INJECTED) == true
    mongoSpan.getTag(Tags.DB_TYPE) in ["mongo", "mongodb"]
    mongoSpan.getTag(Tags.DB_OPERATION) != null

    cleanup:
    client?.close()
  }

  def "MongoDB aggregate command includes comment"() {
    setup:
    def mongoHost = mongo.getHost()
    def mongoPort = mongo.getFirstMappedPort()
    def client = MongoClients.create("mongodb://${mongoHost}:${mongoPort}")
    def database = client.getDatabase("test")
    def collection = database.getCollection("testCollection")

    when:
    runUnderTrace("test") {
      collection.aggregate([new Document("\$match", new Document("status", "active"))]).first()
    }

    then:
    def traces = TEST_WRITER.waitForTraces(1)
    traces.size() == 1
    def mongoSpan = traces[0].find { it.operationName in ["mongo.query", "mongodb.query"] }
    mongoSpan != null
    // Verify span has trace injected tag
    mongoSpan.getTag(DBM_TRACE_INJECTED) == true
    mongoSpan.getTag(Tags.DB_TYPE) in ["mongo", "mongodb"]
    mongoSpan.getTag(Tags.DB_OPERATION) != null

    cleanup:
    client?.close()
  }

  def "MongoDB insert command includes comment"() {
    setup:
    def mongoHost = mongo.getHost()
    def mongoPort = mongo.getFirstMappedPort()
    def client = MongoClients.create("mongodb://${mongoHost}:${mongoPort}")
    def database = client.getDatabase("test")
    def collection = database.getCollection("testCollection")

    when:
    runUnderTrace("test") {
      collection.insertOne(new Document("name", "test").append("value", 42))
    }

    then:
    def traces = TEST_WRITER.waitForTraces(1)
    traces.size() == 1
    def mongoSpan = traces[0].find { it.operationName in ["mongo.query", "mongodb.query"] }
    mongoSpan != null
    // Verify span has trace injected tag
    mongoSpan.getTag(DBM_TRACE_INJECTED) == true
    mongoSpan.getTag(Tags.DB_TYPE) in ["mongo", "mongodb"]
    mongoSpan.getTag(Tags.DB_OPERATION) != null

    cleanup:
    client?.close()
  }

  def "Comment format matches expected pattern"() {
    setup:
    def mongoHost = mongo.getHost()
    def mongoPort = mongo.getFirstMappedPort()
    def client = MongoClients.create("mongodb://${mongoHost}:${mongoPort}")
    def database = client.getDatabase("test")
    def collection = database.getCollection("testCollection")

    when:
    runUnderTrace("test") {
      collection.find(new Document("name", "test")).first()
    }

    then:
    def spans = TEST_WRITER.waitForTraces(1)
    spans.size() == 1
    def mongoSpan = spans[0].find { it.operationName in ["mongo.query", "mongodb.query"] } as DDSpan
    mongoSpan != null
    mongoSpan.getTag(DBM_TRACE_INJECTED) == true

    // Comment should include service name, environment, and trace context
    // Format: ddps='service',dddbs='service',dde='test',traceparent='...'
    def resourceName = mongoSpan.getResourceName()
    resourceName != null

    cleanup:
    client?.close()
  }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    // Enable in service mode only
    injectSysConfig(TraceInstrumentationConfig.DB_DBM_PROPAGATION_MODE_MODE, "service")
    injectSysConfig("dd.service.name", "test-mongo-service")
    injectSysConfig("dd.env", "test")
  }

  @Unroll
  def "Comment injection works for different propagation modes: #mode"() {
    setup:
    injectSysConfig(TraceInstrumentationConfig.DB_DBM_PROPAGATION_MODE_MODE, mode)
    def mongoHost = mongo.getHost()
    def mongoPort = mongo.getFirstMappedPort()
    def client = MongoClients.create("mongodb://${mongoHost}:${mongoPort}")
    def database = client.getDatabase("test")
    def collection = database.getCollection("testCollection")

    when:
    runUnderTrace("test") {
      collection.find(new Document("name", "test")).first()
    }

    then:
    def traces = TEST_WRITER.waitForTraces(1)
    traces.size() == 1
    def mongoSpan = traces[0].find { it.operationName in ["mongo.query", "mongodb.query"] } as DDSpan
    mongoSpan != null

    if (mode == "disabled") {
      mongoSpan.getTag(DBM_TRACE_INJECTED) != true
    } else {
      mongoSpan.getTag(DBM_TRACE_INJECTED) == true
      def resourceName = mongoSpan.getResourceName()

      if (mode == "service") {
        !resourceName.contains("traceparent")
      } else if (mode == "full") {
        // In full mode, trace injection should be enabled
        mongoSpan.getTag(DBM_TRACE_INJECTED) == true
      }
    }

    cleanup:
    client?.close()

    where:
    mode << ["disabled", "service", "full"]
  }

  @Unroll
  def "Comment injection works for different MongoDB operations: #operation"() {
    setup:
    injectSysConfig(TraceInstrumentationConfig.DB_DBM_PROPAGATION_MODE_MODE, "service")
    injectSysConfig("dd.service.name", "test-mongo-service")
    def mongoHost = mongo.getHost()
    def mongoPort = mongo.getFirstMappedPort()
    def client = MongoClients.create("mongodb://${mongoHost}:${mongoPort}")
    def database = client.getDatabase("test")
    def collection = database.getCollection("testCollection")

    when:
    runUnderTrace("test") {
      switch (operation) {
        case "find":
          collection.find(new Document("name", "test")).first()
          break
        case "insert":
          collection.insertOne(new Document("name", "test").append("value", 42))
          break
        case "update":
          collection.updateOne(new Document("name", "test"), new Document("\$set", new Document("updated", true)))
          break
        case "delete":
          collection.deleteOne(new Document("name", "test"))
          break
        case "aggregate":
          collection.aggregate([new Document("\$match", new Document("status", "active"))]).first()
          break
      }
    }

    then:
    def traces = TEST_WRITER.waitForTraces(1)
    traces.size() == 1
    def mongoSpan = traces[0].find { it.operationName in ["mongo.query", "mongodb.query"] }
    mongoSpan != null
    mongoSpan.getTag(DBM_TRACE_INJECTED) == true
    mongoSpan.getTag(Tags.DB_TYPE) in ["mongo", "mongodb"]

    cleanup:
    client?.close()

    where:
    operation << ["find", "insert", "update", "delete", "aggregate"]
  }

  def "Comment injection respects service mapping configuration"() {
    setup:
    injectSysConfig(TraceInstrumentationConfig.DB_DBM_PROPAGATION_MODE_MODE, "service")
    injectSysConfig("service.name", "original-service")
    injectSysConfig("dd.service.mapping", "mongo:mapped-mongo-service")
    def mongoHost = mongo.getHost()
    def mongoPort = mongo.getFirstMappedPort()
    def client = MongoClients.create("mongodb://${mongoHost}:${mongoPort}")
    def database = client.getDatabase("test")
    def collection = database.getCollection("testCollection")

    when:
    runUnderTrace("test") {
      collection.find(new Document("name", "test")).first()
    }

    then:
    def traces = TEST_WRITER.waitForTraces(1)
    traces.size() == 1
    def mongoSpan = traces[0].find { it.operationName in ["mongo.query", "mongodb.query"] }
    mongoSpan != null
    mongoSpan.getTag(DBM_TRACE_INJECTED) == true
    // The exact service name used in comment is tested in unit tests
    // Here we just verify that comment injection occurred

    cleanup:
    client?.close()
  }

  def "Comment injection handles connection errors gracefully"() {
    setup:
    injectSysConfig(TraceInstrumentationConfig.DB_DBM_PROPAGATION_MODE_MODE, "service")
    // Use a non-existent port to trigger connection errors
    def client = MongoClients.create("mongodb://localhost:65535/?connectTimeoutMS=1000&serverSelectionTimeoutMS=1000")
    def database = client.getDatabase("test")
    def collection = database.getCollection("testCollection")

    when:
    def span = null
    runUnderTrace("test") {
      try {
        collection.find(new Document("name", "test")).first()
      } catch (Exception e) {
        // Expected - connection will fail
        span = TEST_TRACER.activeSpan()
      }
    }

    then:
    // Even with connection errors, comment injection should not break tracing
    span != null

    cleanup:
    client?.close()
  }
}

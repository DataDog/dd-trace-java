import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.Config.DBM_PROPAGATION_MODE_FULL
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.event.CommandFailedEvent
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import com.mongodb.event.CommandSucceededEvent
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.core.DDSpan
import org.bson.BsonDocument
import org.bson.BsonString
import org.bson.Document
import spock.lang.Shared

abstract class DefaultServerConnection38InstrumentationTest extends MongoBaseTest {
  @Shared
  MongoClient client

  @Shared
  TestCommandListener commandListener = new TestCommandListener()

  static class TestCommandListener implements CommandListener {
    final List<BsonDocument> commands = []

    @Override
    void commandStarted(CommandStartedEvent event) {
      commands.add(event.getCommand())
    }

    @Override
    void commandSucceeded(CommandSucceededEvent commandSucceededEvent) {
    }

    @Override
    void commandFailed(CommandFailedEvent commandFailedEvent) {
    }
  }

  def setup() throws Exception {
    def connectionString = "mongodb://${mongoDbContainer.getHost()}:$port/?appname=some-description"
    client = MongoClients.create(
      MongoClientSettings.builder()
      .applyConnectionString(new ConnectionString(connectionString))
      .addCommandListener(commandListener)
      .build())
  }

  def cleanup() throws Exception {
    client?.close()
    commandListener.commands.clear()
    client = null
  }
}

abstract class DefaultServerConnection38InstrumentationEnabledTest extends DefaultServerConnection38InstrumentationTest {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TraceInstrumentationConfig.DB_DBM_PROPAGATION_MODE_MODE, DBM_PROPAGATION_MODE_FULL)
  }

  def "test command comment injection"() {
    setup:
    def collectionName = randomCollectionName()
    def db = client.getDatabase(databaseName)

    when:
    runUnderTrace("parent") {
      db.createCollection(collectionName)
    }

    then:
    assertTraces(1) {
      trace(2) {
        sortSpansByStart()
        basicSpan(it, 0,"parent")
        mongoSpan(it, 1, "create", "{\"create\":\"$collectionName\",\"capped\":\"?\",\"comment\":\"?\"}", false, "some-description", span(0), true)
      }
    }

    // Verify command was captured and contains DBM comment
    commandListener.commands.size() > 0
    def createCommand = commandListener.commands.find { it.containsKey("create") }
    createCommand != null

    // Verify the comment contains expected DBM fields
    def comment = createCommand.get("comment")
    comment != null
    def commentStr = comment.asString().getValue()
    commentStr.contains("ddps='")
    commentStr.contains("dddb='database'")
    commentStr.contains("ddh='")
    commentStr.contains("traceparent='")
  }

  def "test insert comment injection"() {
    setup:
    String collectionName = randomCollectionName()
    DDSpan setupSpan = null
    MongoCollection<Document> collection = runUnderTrace("setup") {
      setupSpan = activeSpan() as DDSpan
      MongoDatabase db = client.getDatabase(databaseName)
      db.createCollection(collectionName)
      return db.getCollection(collectionName)
    }
    TEST_WRITER.waitUntilReported(setupSpan)
    TEST_WRITER.clear()

    when:
    collection.insertOne(new Document("password", "SECRET"))
    def estimatedCount = collection.estimatedDocumentCount()
    TEST_WRITER.waitForTraces(2)

    then:
    estimatedCount == 1
    assertTraces(2) {
      trace(1) {
        mongoSpan(it, 0, "insert", "{\"insert\":\"$collectionName\",\"ordered\":true,\"comment\":\"?\",\"documents\":[]}", false, "some-description", null, true)
      }
      trace(1) {
        mongoSpan(it, 0, "count", "{\"count\":\"$collectionName\",\"query\":{},\"comment\":\"?\"}", false, "some-description", null, true)
      }
    }
  }

  def "test update comment injection"() {
    setup:
    String collectionName = randomCollectionName()
    DDSpan setupSpan = null
    MongoCollection<Document> collection = runUnderTrace("setup") {
      setupSpan = activeSpan() as DDSpan
      MongoDatabase db = client.getDatabase(databaseName)
      db.createCollection(collectionName)
      def coll = db.getCollection(collectionName)
      coll.insertOne(new Document("password", "OLDPW"))
      return coll
    }
    TEST_WRITER.waitUntilReported(setupSpan)
    TEST_WRITER.clear()

    when:
    def result = collection.updateOne(
      new BsonDocument("password", new BsonString("OLDPW")),
      new BsonDocument('$set', new BsonDocument("password", new BsonString("NEWPW"))))
    def estimatedCount = collection.estimatedDocumentCount()
    TEST_WRITER.waitForTraces(2)

    then:
    result.modifiedCount == 1
    estimatedCount == 1
    assertTraces(2) {
      trace(1) {
        mongoSpan(it, 0, "update", "{\"update\":\"$collectionName\",\"ordered\":true,\"comment\":\"?\",\"updates\":[]}", false, "some-description", null, true)
      }
      trace(1) {
        mongoSpan(it, 0, "count", "{\"count\":\"$collectionName\",\"query\":{},\"comment\":\"?\"}", false, "some-description", null, true)
      }
    }
  }

  def "test delete comment injection"() {
    setup:
    String collectionName = randomCollectionName()
    DDSpan setupSpan = null
    MongoCollection<Document> collection = runUnderTrace("setup") {
      setupSpan = activeSpan() as DDSpan
      MongoDatabase db = client.getDatabase(databaseName)
      db.createCollection(collectionName)
      def coll = db.getCollection(collectionName)
      coll.insertOne(new Document("password", "SECRET"))
      return coll
    }
    TEST_WRITER.waitUntilReported(setupSpan)
    TEST_WRITER.clear()

    when:
    def result = collection.deleteOne(new BsonDocument("password", new BsonString("SECRET")))
    def estimatedCount = collection.estimatedDocumentCount()
    TEST_WRITER.waitForTraces(2)

    then:
    result.deletedCount == 1
    estimatedCount == 0
    assertTraces(2) {
      trace(1) {
        mongoSpan(it, 0, "delete", "{\"delete\":\"$collectionName\",\"ordered\":true,\"comment\":\"?\",\"deletes\":[]}", false, "some-description", null, true)
      }
      trace(1) {
        mongoSpan(it, 0, "count", "{\"count\":\"$collectionName\",\"query\":{},\"comment\":\"?\"}", false, "some-description", null, true)
      }
    }
  }
}

abstract class DefaultServerConnection38InstrumentationDisabledTest extends DefaultServerConnection38InstrumentationTest {
  def "test command comment not injected when disabled"() {
    setup:
    injectSysConfig("dd.integration.mongo.dbm_propagation.enabled", "false")
    def collectionName = randomCollectionName()
    def db = client.getDatabase(databaseName)

    when:
    db.createCollection(collectionName)

    then:
    assertTraces(1) {
      trace(1) {
        mongoSpan(it, 0, "create", "{\"create\":\"$collectionName\",\"capped\":\"?\"}")
      }
    }

    // Verify command was captured but has no comment
    commandListener.commands.size() > 0
    def createCommand = commandListener.commands.find { it.containsKey("create") }
    createCommand != null
    !createCommand.containsKey("comment")
  }

  def "test insert comment not injected when disabled"() {
    setup:
    String collectionName = randomCollectionName()
    DDSpan setupSpan = null
    MongoCollection<Document> collection = runUnderTrace("setup") {
      setupSpan = activeSpan() as DDSpan
      MongoDatabase db = client.getDatabase(databaseName)
      db.createCollection(collectionName)
      return db.getCollection(collectionName)
    }
    TEST_WRITER.waitUntilReported(setupSpan)
    TEST_WRITER.clear()

    when:
    collection.insertOne(new Document("password", "SECRET"))
    def estimatedCount = collection.estimatedDocumentCount()
    TEST_WRITER.waitForTraces(2)

    then:
    estimatedCount == 1
    assertTraces(2) {
      trace(1) {
        mongoSpan(it, 0, "insert", "{\"insert\":\"$collectionName\",\"ordered\":true,\"documents\":[]}")
      }
      trace(1) {
        mongoSpan(it, 0, "count", "{\"count\":\"$collectionName\",\"query\":{}}")
      }
    }
  }

  def "test update comment not injected when disabled"() {
    setup:
    String collectionName = randomCollectionName()
    DDSpan setupSpan = null
    MongoCollection<Document> collection = runUnderTrace("setup") {
      setupSpan = activeSpan() as DDSpan
      MongoDatabase db = client.getDatabase(databaseName)
      db.createCollection(collectionName)
      def coll = db.getCollection(collectionName)
      coll.insertOne(new Document("password", "OLDPW"))
      return coll
    }
    TEST_WRITER.waitUntilReported(setupSpan)
    TEST_WRITER.clear()

    when:
    def result = collection.updateOne(
      new BsonDocument("password", new BsonString("OLDPW")),
      new BsonDocument('$set', new BsonDocument("password", new BsonString("NEWPW"))))
    def estimatedCount = collection.estimatedDocumentCount()
    TEST_WRITER.waitForTraces(2)

    then:
    result.modifiedCount == 1
    estimatedCount == 1
    assertTraces(2) {
      trace(1) {
        mongoSpan(it, 0, "update", "{\"update\":\"$collectionName\",\"ordered\":true,\"updates\":[]}")
      }
      trace(1) {
        mongoSpan(it, 0, "count", "{\"count\":\"$collectionName\",\"query\":{}}")
      }
    }
  }

  def "test delete comment not injected when disabled"() {
    setup:
    String collectionName = randomCollectionName()
    DDSpan setupSpan = null
    MongoCollection<Document> collection = runUnderTrace("setup") {
      setupSpan = activeSpan() as DDSpan
      MongoDatabase db = client.getDatabase(databaseName)
      db.createCollection(collectionName)
      def coll = db.getCollection(collectionName)
      coll.insertOne(new Document("password", "SECRET"))
      return coll
    }
    TEST_WRITER.waitUntilReported(setupSpan)
    TEST_WRITER.clear()

    when:
    def result = collection.deleteOne(new BsonDocument("password", new BsonString("SECRET")))
    def estimatedCount = collection.estimatedDocumentCount()
    TEST_WRITER.waitForTraces(2)

    then:
    result.deletedCount == 1
    estimatedCount == 0
    assertTraces(2) {
      trace(1) {
        mongoSpan(it, 0, "delete", "{\"delete\":\"$collectionName\",\"ordered\":true,\"deletes\":[]}")
      }
      trace(1) {
        mongoSpan(it, 0, "count", "{\"count\":\"$collectionName\",\"query\":{}}")
      }
    }
  }
}

// Test class with DBM propagation enabled by default
class DefaultServerConnection38InstrumentationEnabledV0ForkedTest extends DefaultServerConnection38InstrumentationEnabledTest {
  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return V0_SERVICE
  }

  @Override
  String operation() {
    return V0_OPERATION
  }

  @Override
  String dbType() {
    return V0_DB_TYPE
  }
}

// Test class with service name mapping
class DefaultServerConnection38InstrumentationEnabledV1ForkedTest extends DefaultServerConnection38InstrumentationEnabledTest {
  @Override
  int version() {
    return 1
  }

  @Override
  String service() {
    return V1_SERVICE
  }

  @Override
  String operation() {
    return V1_OPERATION
  }

  @Override
  String dbType() {
    return V1_DB_TYPE
  }
}

// Test class with DBM propagation enabled by default
class DefaultServerConnection38InstrumentationDisabledV0Test extends DefaultServerConnection38InstrumentationDisabledTest {
  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return V0_SERVICE
  }

  @Override
  String operation() {
    return V0_OPERATION
  }

  @Override
  String dbType() {
    return V0_DB_TYPE
  }
}

// Test class with service name mapping
class DefaultServerConnection38InstrumentationDisabledV1ForkedTest extends DefaultServerConnection38InstrumentationDisabledTest {
  @Override
  int version() {
    return 1
  }

  @Override
  String service() {
    return V1_SERVICE
  }

  @Override
  String operation() {
    return V1_OPERATION
  }

  @Override
  String dbType() {
    return V1_DB_TYPE
  }
}

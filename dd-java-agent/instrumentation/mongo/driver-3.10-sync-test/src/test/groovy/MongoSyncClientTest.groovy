import com.mongodb.MongoTimeoutException
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import datadog.trace.core.DDSpan
import org.bson.BsonDocument
import org.bson.BsonString
import org.bson.Document
import spock.lang.Shared

import static datadog.trace.agent.test.utils.PortUtils.UNUSABLE_PORT
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

abstract class MongoSyncClientTest extends MongoBaseTest {

  @Shared
  MongoClient client

  def setup() throws Exception {
    client = MongoClients.create("mongodb://${mongoDbContainer.getHost()}:$port/?appname=some-description")
  }

  def cleanup() throws Exception {
    client?.close()
    client = null
  }

  def "test create collection with renameService=#renameService"() {
    setup:
    String collectionName = randomCollectionName()
    MongoDatabase db = client.getDatabase(databaseName)
    injectSysConfig(DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "$renameService")

    when:
    db.createCollection(collectionName)
    TEST_WRITER.waitForTraces(1)

    then:
    assertTraces(1) {
      trace(1) {
        mongoSpan(it, 0, "create", "{\"create\":\"$collectionName\",\"capped\":\"?\"}", renameService)
      }
    }

    where:
    renameService << [false, true]
  }

  def "test create collection no description"() {
    setup:
    String collectionName = randomCollectionName()
    MongoDatabase db = MongoClients.create("mongodb://${mongoDbContainer.getHost()}:$port").getDatabase(databaseName)

    when:
    db.createCollection(collectionName)

    then:
    assertTraces(1) {
      trace(1) {
        mongoSpan(it, 0, "create", "{\"create\":\"$collectionName\",\"capped\":\"?\"}", false, databaseName)
      }
    }
  }

  def "test get collection"() {
    setup:
    String collectionName = randomCollectionName()
    MongoDatabase db = client.getDatabase(databaseName)

    when:
    int count = db.getCollection(collectionName).estimatedDocumentCount()
    TEST_WRITER.waitForTraces(1)
    then:
    count == 0
    assertTraces(1) {
      trace(1) {
        mongoSpan(it, 0, "count", "{\"count\":\"$collectionName\",\"query\":{}}")
      }
    }
  }

  def "test insert"() {
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

  def "test update"() {
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

  def "test delete"() {
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

  def "test error"() {
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
    collection.updateOne(new BsonDocument(), new BsonDocument())

    then:
    thrown(IllegalArgumentException)
    // Unfortunately not caught by our instrumentation.
    assertTraces(0) {}
  }

  def "test client failure"() {
    setup:
    String collectionName = randomCollectionName()
    def client = MongoClients.create("mongodb://${mongoDbContainer.getHost()}:$UNUSABLE_PORT/?serverselectiontimeoutms=10")

    when:
    MongoDatabase db = client.getDatabase(databaseName)
    db.createCollection(collectionName)

    then:
    thrown(MongoTimeoutException)
    // Unfortunately not caught by our instrumentation.
    assertTraces(0) {}
  }
}

class MongoSyncClientV0Test extends MongoSyncClientTest {

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

class MongoSyncClientV1ForkedTest extends MongoSyncClientTest {

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

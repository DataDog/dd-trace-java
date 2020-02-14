import com.mongodb.MongoClient
import com.mongodb.MongoClientOptions
import com.mongodb.MongoTimeoutException
import com.mongodb.ServerAddress
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import datadog.opentracing.DDSpan
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.bson.BsonDocument
import org.bson.BsonString
import org.bson.Document
import spock.lang.Shared

import static datadog.trace.agent.test.utils.ConfigUtils.withConfigOverride
import static datadog.trace.agent.test.utils.PortUtils.UNUSABLE_PORT
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class MongoClientTest extends MongoBaseTest {

  @Shared
  MongoClient client

  def setup() throws Exception {
    client = new MongoClient(new ServerAddress("localhost", port),
      MongoClientOptions.builder()
        .description("some-description")
        .build())
  }

  def cleanup() throws Exception {
    client?.close()
    client = null
  }

  def "test create collection"() {
    setup:
    MongoDatabase db = client.getDatabase(dbName)

    when:
    withConfigOverride(Config.DB_CLIENT_HOST_SPLIT_BY_INSTANCE, "$renameService") {
      db.createCollection(collectionName)
    }

    then:
    assertTraces(1) {
      trace(0, 1) {
        mongoSpan(it, 0, "{\"create\":\"$collectionName\",\"capped\":\"?\"}", renameService)
      }
    }

    where:
    dbName = "test_db"
    collectionName = "testCollection"
    renameService << [false, true]
  }

  def "test create collection no description"() {
    setup:
    MongoDatabase db = new MongoClient("localhost", port).getDatabase(dbName)

    when:
    db.createCollection(collectionName)

    then:
    assertTraces(1) {
      trace(0, 1) {
        mongoSpan(it, 0, "{\"create\":\"$collectionName\",\"capped\":\"?\"}", false, dbName)
      }
    }

    where:
    dbName = "test_db"
    collectionName = "testCollection"
  }

  def "test get collection"() {
    setup:
    MongoDatabase db = client.getDatabase(dbName)

    when:
    int count = db.getCollection(collectionName).count()

    then:
    count == 0
    assertTraces(1) {
      trace(0, 1) {
        mongoSpan(it, 0, "{\"count\":\"$collectionName\",\"query\":{}}")
      }
    }

    where:
    dbName = "test_db"
    collectionName = "testCollection"
  }

  def "test insert"() {
    setup:
    MongoCollection<Document> collection = runUnderTrace("setup") {
      MongoDatabase db = client.getDatabase(dbName)
      db.createCollection(collectionName)
      return db.getCollection(collectionName)
    }
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.clear()

    when:
    collection.insertOne(new Document("password", "SECRET"))

    then:
    collection.count() == 1
    assertTraces(2) {
      trace(0, 1) {
        mongoSpan(it, 0, "{\"insert\":\"$collectionName\",\"ordered\":\"?\",\"documents\":[{\"_id\":\"?\",\"password\":\"?\"}]}")
      }
      trace(1, 1) {
        mongoSpan(it, 0, "{\"count\":\"$collectionName\",\"query\":{}}")
      }
    }

    where:
    dbName = "test_db"
    collectionName = "testCollection"
  }

  def "test update"() {
    setup:
    MongoCollection<Document> collection = runUnderTrace("setup") {
      MongoDatabase db = client.getDatabase(dbName)
      db.createCollection(collectionName)
      def coll = db.getCollection(collectionName)
      coll.insertOne(new Document("password", "OLDPW"))
      return coll
    }
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.clear()

    when:
    def result = collection.updateOne(
      new BsonDocument("password", new BsonString("OLDPW")),
      new BsonDocument('$set', new BsonDocument("password", new BsonString("NEWPW"))))

    then:
    result.modifiedCount == 1
    collection.count() == 1
    assertTraces(2) {
      trace(0, 1) {
        mongoSpan(it, 0, "{\"update\":\"?\",\"ordered\":\"?\",\"updates\":[{\"q\":{\"password\":\"?\"},\"u\":{\"\$set\":{\"password\":\"?\"}}}]}")
      }
      trace(1, 1) {
        mongoSpan(it, 0, "{\"count\":\"$collectionName\",\"query\":{}}")
      }
    }

    where:
    dbName = "test_db"
    collectionName = "testCollection"
  }

  def "test delete"() {
    setup:
    MongoCollection<Document> collection = runUnderTrace("setup") {
      MongoDatabase db = client.getDatabase(dbName)
      db.createCollection(collectionName)
      def coll = db.getCollection(collectionName)
      coll.insertOne(new Document("password", "SECRET"))
      return coll
    }
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.clear()

    when:
    def result = collection.deleteOne(new BsonDocument("password", new BsonString("SECRET")))

    then:
    result.deletedCount == 1
    collection.count() == 0
    assertTraces(2) {
      trace(0, 1) {
        mongoSpan(it, 0, "{\"delete\":\"?\",\"ordered\":\"?\",\"deletes\":[{\"q\":{\"password\":\"?\"},\"limit\":\"?\"}]}")
      }
      trace(1, 1) {
        mongoSpan(it, 0, "{\"count\":\"$collectionName\",\"query\":{}}")
      }
    }

    where:
    dbName = "test_db"
    collectionName = "testCollection"
  }

  def "test error"() {
    setup:
    MongoCollection<Document> collection = runUnderTrace("setup") {
      MongoDatabase db = client.getDatabase(dbName)
      db.createCollection(collectionName)
      return db.getCollection(collectionName)
    }
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.clear()

    when:
    collection.updateOne(new BsonDocument(), new BsonDocument())

    then:
    thrown(IllegalArgumentException)
    // Unfortunately not caught by our instrumentation.
    assertTraces(0) {}

    where:
    dbName = "test_db"
    collectionName = "testCollection"
  }

  def "test client failure"() {
    setup:
    def options = MongoClientOptions.builder().serverSelectionTimeout(10).build()
    def client = new MongoClient(new ServerAddress("localhost", UNUSABLE_PORT), [], options)

    when:
    MongoDatabase db = client.getDatabase(dbName)
    db.createCollection(collectionName)

    then:
    thrown(MongoTimeoutException)
    // Unfortunately not caught by our instrumentation.
    assertTraces(0) {}

    where:
    dbName = "test_db"
    collectionName = "testCollection"
  }

  def mongoSpan(TraceAssert trace, int index, String statement, boolean renameService = false, String instance = "some-description", Object parentSpan = null, Throwable exception = null) {
    trace.span(index) {
      serviceName renameService ? instance : "mongo"
      operationName "mongo.query"
      resourceName {
        assert it.replace(" ", "") == statement
        return true
      }
      spanType DDSpanTypes.MONGO
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
      tags {
        "$Tags.COMPONENT" "java-mongo"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.PEER_HOSTNAME" "localhost"
        "$Tags.PEER_HOST_IPV4" "127.0.0.1"
        "$Tags.PEER_PORT" port
        "$Tags.DB_STATEMENT" {
          it.replace(" ", "") == statement
        }
        "$Tags.DB_TYPE" "mongo"
        "$Tags.DB_INSTANCE" instance
        defaultTags()
      }
    }
  }
}

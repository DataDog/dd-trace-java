import com.mongodb.MongoTimeoutException
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.bson.BsonDocument
import org.bson.BsonString
import org.bson.Document
import spock.lang.Shared

import static datadog.trace.agent.test.utils.PortUtils.UNUSABLE_PORT
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.Checkpointer.END
import static datadog.trace.api.Checkpointer.SPAN
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE

class Mongo4ClientTest extends MongoBaseTest {

  @Shared
  MongoClient client

  def setup() throws Exception {
    client = MongoClients.create("mongodb://localhost:$port/?appname=some-instance")
  }

  def cleanup() throws Exception {
    client?.close()
    client = null
  }

  def "test create collection"() {
    setup:
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
    and: "synchronous checkpoints span the driver activity"
    1 * TEST_CHECKPOINTER.checkpoint(_, SPAN)
    1 * TEST_CHECKPOINTER.checkpoint(_, SPAN | END)
    _ * TEST_CHECKPOINTER.onRootSpanWritten(_, _, _)
    _ * TEST_CHECKPOINTER.onRootSpanStarted(_)
    0 * TEST_CHECKPOINTER._

    where:
    collectionName = randomCollectionName()
    renameService << [false, true]
  }

  def "test create collection no description"() {
    setup:
    MongoDatabase db = MongoClients.create("mongodb://localhost:$port").getDatabase(databaseName)

    when:
    db.createCollection(collectionName)

    then:
    assertTraces(1) {
      trace(1) {
        mongoSpan(it, 0, "create", "{\"create\":\"$collectionName\",\"capped\":\"?\"}", false, databaseName)
      }
    }

    where:
    collectionName = randomCollectionName()
  }

  def "test get collection"() {
    setup:
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
    and: "synchronous checkpoints span the driver activity"
    1 * TEST_CHECKPOINTER.checkpoint(_, SPAN)
    1 * TEST_CHECKPOINTER.checkpoint(_, SPAN | END)
    _ * TEST_CHECKPOINTER.onRootSpanWritten(_, _, _)
    _ * TEST_CHECKPOINTER.onRootSpanStarted(_)
    0 * TEST_CHECKPOINTER._

    where:
    collectionName = randomCollectionName()
  }

  def "test insert"() {
    setup:
    MongoCollection<Document> collection = runUnderTrace("setup") {
      MongoDatabase db = client.getDatabase(databaseName)
      db.createCollection(collectionName)
      return db.getCollection(collectionName)
    }
    TEST_WRITER.waitForTraces(1)
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
    and: "syncronous checkpoints span the driver activity"
    2 * TEST_CHECKPOINTER.checkpoint(_, SPAN)
    2 * TEST_CHECKPOINTER.checkpoint(_, SPAN | END)
    _ * TEST_CHECKPOINTER.onRootSpanWritten(_, _, _)
    _ * TEST_CHECKPOINTER.onRootSpanStarted(_)
    0 * TEST_CHECKPOINTER._

    where:
    collectionName = randomCollectionName()
  }

  def "test update"() {
    setup:
    MongoCollection<Document> collection = runUnderTrace("setup") {
      MongoDatabase db = client.getDatabase(databaseName)
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
    and: "syncronous checkpoints span the driver activity"
    2 * TEST_CHECKPOINTER.checkpoint(_, SPAN)
    2 * TEST_CHECKPOINTER.checkpoint(_, SPAN | END)
    _ * TEST_CHECKPOINTER.onRootSpanWritten(_, _, _)
    _ * TEST_CHECKPOINTER.onRootSpanStarted(_)
    0 * TEST_CHECKPOINTER._

    where:
    collectionName = randomCollectionName()
  }

  def "test delete"() {
    setup:
    MongoCollection<Document> collection = runUnderTrace("setup") {
      MongoDatabase db = client.getDatabase(databaseName)
      db.createCollection(collectionName)
      def coll = db.getCollection(collectionName)
      coll.insertOne(new Document("password", "SECRET"))
      return coll
    }
    TEST_WRITER.waitForTraces(1)
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
    and: "syncronous checkpoints span the driver activity"
    2 * TEST_CHECKPOINTER.checkpoint(_, SPAN)
    2 * TEST_CHECKPOINTER.checkpoint(_, SPAN | END)
    _ * TEST_CHECKPOINTER.onRootSpanWritten(_, _, _)
    _ * TEST_CHECKPOINTER.onRootSpanStarted(_)
    0 * TEST_CHECKPOINTER._

    where:
    collectionName = randomCollectionName()
  }

  def "test error"() {
    setup:
    MongoCollection<Document> collection = runUnderTrace("setup") {
      MongoDatabase db = client.getDatabase(databaseName)
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
    collectionName = randomCollectionName()
  }

  def "test client failure"() {
    setup:
    def client = MongoClients.create("mongodb://localhost:$UNUSABLE_PORT/?serverselectiontimeoutms=10")

    when:
    MongoDatabase db = client.getDatabase(databaseName)
    db.createCollection(collectionName)

    then:
    thrown(MongoTimeoutException)
    // Unfortunately not caught by our instrumentation.
    assertTraces(0) {}

    where:
    collectionName = randomCollectionName()
  }

  def mongoSpan(TraceAssert trace, int index, String operation, String statement, boolean renameService = false, String instance = "some-instance", Object parentSpan = null, Throwable exception = null) {
    trace.span {
      serviceName renameService ? instance : "mongo"
      operationName "mongo.query"
      resourceName matchesStatement(statement)
      spanType DDSpanTypes.MONGO
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
      topLevel true
      tags {
        "$Tags.COMPONENT" "java-mongo"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.PEER_HOSTNAME" "localhost"
        "$Tags.PEER_PORT" port
        "$Tags.DB_TYPE" "mongo"
        "$Tags.DB_INSTANCE" instance
        "$Tags.DB_OPERATION" operation
        defaultTags()
      }
    }
  }
}

import com.mongodb.ConnectionString
import com.mongodb.async.SingleResultCallback
import com.mongodb.async.client.MongoClient
import com.mongodb.async.client.MongoClientSettings
import com.mongodb.async.client.MongoClients
import com.mongodb.async.client.MongoCollection
import com.mongodb.async.client.MongoDatabase
import com.mongodb.client.result.DeleteResult
import com.mongodb.client.result.UpdateResult
import com.mongodb.connection.ClusterSettings
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.bson.BsonDocument
import org.bson.BsonString
import org.bson.Document
import spock.lang.Shared
import spock.lang.Timeout

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

@Timeout(10)
class MongoAsyncClientTest extends MongoBaseTest {

  @Shared
  MongoClient client

  def setup() throws Exception {
    client = MongoClients.create(
      MongoClientSettings.builder()
      .clusterSettings(
      ClusterSettings.builder()
      .description("some-description")
      .applyConnectionString(new ConnectionString("mongodb://localhost:$port"))
      .build())
      .build())
  }

  def cleanup() throws Exception {
    client?.close()
    client = null
  }

  def "test create collection"() {
    setup:
    CheckpointValidator.excludeAllValidations()
    MongoDatabase db = client.getDatabase(databaseName)

    when:
    db.createCollection(collectionName, toCallback {})

    then:
    assertTraces(1) {
      trace(1) {
        mongoSpan(it, 0, "create", "{\"create\":\"$collectionName\",\"capped\":\"?\"}")
      }
    }

    where:
    collectionName = randomCollectionName()
  }

  def "test create collection no description"() {
    setup:
    CheckpointValidator.excludeAllValidations()
    MongoDatabase db = MongoClients.create("mongodb://localhost:$port").getDatabase(databaseName)

    when:
    db.createCollection(collectionName, toCallback {})

    then:
    assertTraces(1) {
      trace(1) {
        mongoSpan(it, 0, "create", "{\"create\":\"$collectionName\",\"capped\":\"?\"}", databaseName)
      }
    }

    where:
    collectionName = randomCollectionName()
  }

  def "test get collection"() {
    setup:
    CheckpointValidator.excludeAllValidations()
    MongoDatabase db = client.getDatabase(databaseName)

    when:
    def count = new CompletableFuture()
    db.getCollection(collectionName).count toCallback { count.complete(it) }

    then:
    count.get() == 0
    assertTraces(1) {
      trace(1) {
        mongoSpan(it, 0, "count", "{\"count\":\"$collectionName\",\"query\":{}}")
      }
    }

    where:
    collectionName = randomCollectionName()
  }

  def "test insert"() {
    setup:
    CheckpointValidator.excludeAllValidations()
    MongoCollection<Document> collection = runUnderTrace("setup") {
      MongoDatabase db = client.getDatabase(databaseName)
      def latch1 = new CountDownLatch(1)
      // This creates a trace that isn't linked to the parent... using NIO internally that we don't handle.
      db.createCollection(collectionName, toCallback { latch1.countDown() })
      latch1.await()
      return db.getCollection(collectionName)
    }
    TEST_WRITER.waitForTraces(2)
    TEST_WRITER.clear()

    when:
    def count = new CompletableFuture()
    collection.insertOne(new Document("password", "SECRET"), toCallback {
      collection.count toCallback { count.complete(it) }
    })

    then:
    count.get() == 1
    assertTraces(2) {
      trace(1) {
        mongoSpan(it, 0, "insert", "{\"insert\":\"$collectionName\",\"ordered\":true,\"documents\":[]}")
      }
      trace(1) {
        mongoSpan(it, 0, "count", "{\"count\":\"$collectionName\",\"query\":{}}")
      }
    }

    where:
    collectionName = randomCollectionName()
  }

  def "test update"() {
    setup:
    CheckpointValidator.excludeAllValidations()
    MongoCollection<Document> collection = runUnderTrace("setup") {
      MongoDatabase db = client.getDatabase(databaseName)
      def latch1 = new CountDownLatch(1)
      db.createCollection(collectionName, toCallback { latch1.countDown() })
      latch1.await()
      def coll = db.getCollection(collectionName)
      def latch2 = new CountDownLatch(1)
      coll.insertOne(new Document("password", "OLDPW"), toCallback { latch2.countDown() })
      latch2.await()
      return coll
    }
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.clear()

    when:
    def result = new CompletableFuture<UpdateResult>()
    def count = new CompletableFuture()
    collection.updateOne(
      new BsonDocument("password", new BsonString("OLDPW")),
      new BsonDocument('$set', new BsonDocument("password", new BsonString("NEWPW"))), toCallback {
        result.complete(it)
        collection.count toCallback { count.complete(it) }
      })

    then:
    result.get().modifiedCount == 1
    count.get() == 1
    assertTraces(2) {
      trace(1) {
        mongoSpan(it, 0, "update", "{\"update\":\"$collectionName\",\"ordered\":true,\"updates\":[]}")
      }
      trace(1) {
        mongoSpan(it, 0, "count", "{\"count\":\"$collectionName\",\"query\":{}}")
      }
    }

    where:
    collectionName = randomCollectionName()
  }

  def "test delete"() {
    setup:
    CheckpointValidator.excludeAllValidations()
    MongoCollection<Document> collection = runUnderTrace("setup") {
      MongoDatabase db = client.getDatabase(databaseName)
      def latch1 = new CountDownLatch(1)
      db.createCollection(collectionName, toCallback { latch1.countDown() })
      latch1.await()
      def coll = db.getCollection(collectionName)
      def latch2 = new CountDownLatch(1)
      coll.insertOne(new Document("password", "SECRET"), toCallback { latch2.countDown() })
      latch2.await()
      return coll
    }
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.clear()

    when:
    def result = new CompletableFuture<DeleteResult>()
    def count = new CompletableFuture()
    collection.deleteOne(new BsonDocument("password", new BsonString("SECRET")), toCallback {
      result.complete(it)
      collection.count toCallback { count.complete(it) }
    })

    then:
    result.get().deletedCount == 1
    count.get() == 0
    assertTraces(2) {
      trace(1) {
        mongoSpan(it, 0, "delete", "{\"delete\":\"$collectionName\",\"ordered\":true,\"deletes\":[]}")
      }
      trace(1) {
        mongoSpan(it, 0, "count", "{\"count\":\"$collectionName\",\"query\":{}}")
      }
    }

    where:
    collectionName = randomCollectionName()
  }

  SingleResultCallback toCallback(Closure closure) {
    return new SingleResultCallback() {
        @Override
        void onResult(Object result, Throwable t) {
          if (t) {
            closure.call(t)
          } else {
            closure.call(result)
          }
        }
      }
  }

  def mongoSpan(TraceAssert trace, int index, String operation, String statement, String instance = "some-description", Object parentSpan = null, Throwable exception = null) {
    trace.span {
      serviceName "mongo"
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

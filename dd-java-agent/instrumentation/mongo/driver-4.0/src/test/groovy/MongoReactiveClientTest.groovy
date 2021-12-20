import com.mongodb.client.result.DeleteResult
import com.mongodb.client.result.UpdateResult
import com.mongodb.reactivestreams.client.MongoClient
import com.mongodb.reactivestreams.client.MongoClients
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.bson.BsonDocument
import org.bson.BsonString
import org.bson.Document
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import spock.lang.Shared
import spock.lang.Timeout

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

@Timeout(10)
class MongoReactiveClientTest extends MongoBaseTest {

  @Shared
  MongoClient client

  def setup() throws Exception {
    client = MongoClients.create("mongodb://localhost:$port/?appname=some-instance")
  }

  def cleanup() throws Exception {
    client?.close()
    client = null
  }

  MongoCollection<Document> setupCollection(String collectionName) {
    MongoCollection<Document> collection = runUnderTrace("setup") {
      MongoDatabase db = client.getDatabase(databaseName)
      def latch = new CountDownLatch(1)
      db.createCollection(collectionName).subscribe(toSubscriber { latch.countDown() })
      latch.await()
      return db.getCollection(collectionName)
    }
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.clear()
    return collection
  }

  void insertDocument(MongoCollection<Document> collection, Document document, Subscriber<?> subscriber) {
    def publisher = collection.insertOne(document)
    if (null != subscriber) {
      publisher.subscribe(subscriber)
    } else {
      def latch = new CountDownLatch(1)
      publisher.subscribe(toSubscriber {
        latch.countDown()
      })
      latch.await()
      TEST_WRITER.waitForTraces(1)
      TEST_WRITER.clear()
    }
  }

  def "test create collection"() {
    setup:
    MongoDatabase db = client.getDatabase(databaseName)

    when:
    db.createCollection(collectionName).subscribe(toSubscriber {})

    then:
    assertTraces(1) {
      trace(1) {
        mongoSpan(it, 0, "create", "{\"create\":\"$collectionName\",\"capped\":\"?\"}")
      }
    }

    where:
    collectionName = randomCollectionName()
  }

  def "test create collection with parent"() {
    setup:
    MongoDatabase db = client.getDatabase(databaseName)

    when:
    runUnderTrace("parent") {
      db.createCollection(collectionName).subscribe(toSubscriber {})
    }

    then:
    assertTraces(1) {
      trace(2) {
        sortSpansByStart()
        basicSpan(it, 0,"parent")
        mongoSpan(it, 1, "create", "{\"create\":\"$collectionName\",\"capped\":\"?\"}", "some-instance", span(0))
      }
    }

    where:
    collectionName = randomCollectionName()
  }

  def "test create collection no description"() {
    setup:
    MongoDatabase db = MongoClients.create("mongodb://localhost:$port").getDatabase(databaseName)

    when:
    db.createCollection(collectionName).subscribe(toSubscriber {})

    then:
    assertTraces(1) {
      trace(1) {
        mongoSpan(it, 0, "create", "{\"create\":\"$collectionName\",\"capped\":\"?\"}", databaseName)
      }
    }

    where:
    collectionName = randomCollectionName()
  }

  def "test create collection no description with parent"() {
    setup:
    MongoDatabase db = MongoClients.create("mongodb://localhost:$port").getDatabase(databaseName)

    when:
    runUnderTrace("parent") {
      db.createCollection(collectionName).subscribe(toSubscriber {})
    }

    then:
    assertTraces(1) {
      trace(2) {
        sortSpansByStart()
        basicSpan(it, 0,"parent")
        mongoSpan(it, 1, "create", "{\"create\":\"$collectionName\",\"capped\":\"?\"}", databaseName, span(0))
      }
    }

    where:
    collectionName = randomCollectionName()
  }

  def "test get collection"() {
    setup:
    MongoDatabase db = client.getDatabase(databaseName)

    when:
    def count = new CompletableFuture()
    db.getCollection(collectionName).estimatedDocumentCount().subscribe(toSubscriber { count.complete(it) })

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

  def "test get collection with parent"() {
    setup:
    MongoDatabase db = client.getDatabase(databaseName)

    when:
    def count = new CompletableFuture()
    runUnderTrace("parent") {
      db.getCollection(collectionName).estimatedDocumentCount().subscribe(toSubscriber { count.complete(it) })
    }

    then:
    count.get() == 0
    assertTraces(1) {
      trace(2) {
        sortSpansByStart()
        basicSpan(it, 0,"parent")
        mongoSpan(it, 1, "count", "{\"count\":\"$collectionName\",\"query\":{}}", "some-instance", span(0))
      }
    }

    where:
    collectionName = randomCollectionName()
  }

  def "test insert"() {
    setup:
    def collection = setupCollection(collectionName)

    when:
    def count = new CompletableFuture()
    insertDocument(collection, new Document("password", "SECRET"), toSubscriber {
      collection.estimatedDocumentCount().subscribe(toSubscriber { count.complete(it) })
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

  def "test insert with parent"() {
    setup:
    def collection = setupCollection(collectionName)

    when:
    def count = new CompletableFuture()
    runUnderTrace("parent") {
      insertDocument(collection, new Document("password", "SECRET"), toSubscriber {
        collection.estimatedDocumentCount().subscribe(toSubscriber { count.complete(it) })
      })
    }

    then:
    count.get() == 1
    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        basicSpan(it, 0,"parent")
        mongoSpan(it, 1, "insert", "{\"insert\":\"$collectionName\",\"ordered\":true,\"documents\":[]}", "some-instance", span(0))
        mongoSpan(it, 2, "count", "{\"count\":\"$collectionName\",\"query\":{}}", "some-instance", span(0))
      }
    }

    where:
    collectionName = randomCollectionName()
  }

  def "test update"() {
    setup:
    MongoCollection<Document> collection = setupCollection(collectionName)
    insertDocument(collection, new Document("password", "OLDPW"), null)

    when:
    def result = new CompletableFuture<UpdateResult>()
    def count = new CompletableFuture()
    collection.updateOne(
      new BsonDocument("password", new BsonString("OLDPW")),
      new BsonDocument('$set', new BsonDocument("password", new BsonString("NEWPW")))).subscribe(toSubscriber {
        result.complete(it)
        collection.estimatedDocumentCount().subscribe(toSubscriber { count.complete(it) })
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

  def "test update with parent"() {
    setup:
    MongoCollection<Document> collection = setupCollection(collectionName)
    insertDocument(collection, new Document("password", "OLDPW"), null)

    when:
    def result = new CompletableFuture<UpdateResult>()
    def count = new CompletableFuture()
    runUnderTrace("parent") {
      collection.updateOne(
        new BsonDocument("password", new BsonString("OLDPW")),
        new BsonDocument('$set', new BsonDocument("password", new BsonString("NEWPW")))).subscribe(toSubscriber {
          result.complete(it)
          collection.estimatedDocumentCount().subscribe(toSubscriber { count.complete(it) })
        })
    }

    then:
    result.get().modifiedCount == 1
    count.get() == 1
    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        basicSpan(it, 0,"parent")
        mongoSpan(it, 1, "update", "{\"update\":\"$collectionName\",\"ordered\":true,\"updates\":[]}", "some-instance", span(0))
        mongoSpan(it, 2, "count", "{\"count\":\"$collectionName\",\"query\":{}}", "some-instance", span(0))
      }
    }

    where:
    collectionName = randomCollectionName()
  }

  def "test delete"() {
    setup:
    MongoCollection<Document> collection = setupCollection(collectionName)
    insertDocument(collection, new Document("password", "SECRET"), null)

    when:
    def result = new CompletableFuture<DeleteResult>()
    def count = new CompletableFuture()
    collection.deleteOne(new BsonDocument("password", new BsonString("SECRET"))).subscribe(toSubscriber {
      result.complete(it)
      collection.estimatedDocumentCount().subscribe(toSubscriber { count.complete(it) })
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

  def "test delete with parent"() {
    setup:
    MongoCollection<Document> collection = setupCollection(collectionName)
    insertDocument(collection, new Document("password", "SECRET"), null)

    when:
    def result = new CompletableFuture<DeleteResult>()
    def count = new CompletableFuture()
    runUnderTrace("parent") {
      collection.deleteOne(new BsonDocument("password", new BsonString("SECRET"))).subscribe(toSubscriber {
        result.complete(it)
        collection.estimatedDocumentCount().subscribe(toSubscriber { count.complete(it) })
      })
    }

    then:
    result.get().deletedCount == 1
    count.get() == 0
    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        basicSpan(it, 0,"parent")
        mongoSpan(it, 1, "delete", "{\"delete\":\"$collectionName\",\"ordered\":true,\"deletes\":[]}", "some-instance", span(0))
        mongoSpan(it, 2, "count", "{\"count\":\"$collectionName\",\"query\":{}}", "some-instance", span(0))
      }
    }

    where:
    collectionName = randomCollectionName()
  }

  def Subscriber<?> toSubscriber(Closure closure) {
    return new Subscriber() {
        boolean hasResult

        @Override
        void onSubscribe(Subscription s) {
          s.request(1) // must request 1 value to trigger async call
        }

        @Override
        void onNext(Object o) {
          hasResult = true; closure.call(o)
        }

        @Override
        void onError(Throwable t) {
          hasResult = true; closure.call(t)
        }

        @Override
        void onComplete() {
          if (!hasResult) {
            hasResult = true
            closure.call()
          }
        }
      }
  }

  def mongoSpan(TraceAssert trace, int index, String operation, String statement, String instance = "some-instance", Object parentSpan = null, Throwable exception = null) {
    trace.span(index) {
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

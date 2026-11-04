import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.MongoCommandException
import com.mongodb.event.CommandFailedEvent
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import com.mongodb.event.CommandSucceededEvent
import com.mongodb.client.result.DeleteResult
import com.mongodb.client.result.UpdateResult
import com.mongodb.internal.build.MongoDriverVersion
import com.mongodb.reactivestreams.client.MongoClient
import com.mongodb.reactivestreams.client.MongoClients
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import datadog.trace.core.DDSpan
import org.bson.BsonDocument
import org.bson.BsonString
import org.bson.Document
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.spockframework.util.VersionNumber
import spock.lang.Shared

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static java.util.concurrent.TimeUnit.SECONDS

abstract class MongoReactiveClientTest extends MongoBaseTest {

  @Shared
  MongoClient client

  def setup() throws Exception {
    client = MongoClients.create("mongodb://${mongoDbContainer.getHost()}:$port/?appname=some-description")
  }

  def cleanup() throws Exception {
    client?.close()
    client = null
  }

  @Shared
  String query = {
    def version  = VersionNumber.parse(MongoDriverVersion.VERSION)
    if (version.major == 4 && version.minor < 3) {
      // query is returned for versions < 4.3
      return ',"query":{}'
    }
    return ''
  }.call()

  MongoCollection<Document> setupCollection(String collectionName) {
    DDSpan setupSpan = null
    MongoCollection<Document> collection = runUnderTrace("setup") {
      setupSpan = activeSpan() as DDSpan
      MongoDatabase db = client.getDatabase(databaseName)
      def latch = new CountDownLatch(1)
      db.createCollection(collectionName).subscribe(toSubscriber { latch.countDown() })
      latch.await()
      return db.getCollection(collectionName)
    }
    TEST_WRITER.waitUntilReported(setupSpan)
    TEST_WRITER.clear()
    return collection
  }

  void insertDocument(MongoCollection<Document> collection, Document document, Subscriber<?> subscriber) {
    def publisher = collection.insertOne(document)
    if (null != subscriber) {
      publisher.subscribe(subscriber)
    } else {
      DDSpan setupSpan = runUnderTrace("setup") {
        def latch = new CountDownLatch(1)
        publisher.subscribe(toSubscriber {
          latch.countDown()
        })
        latch.await()
        return activeSpan() as DDSpan
      }
      TEST_WRITER.waitUntilReported(setupSpan)
      TEST_WRITER.clear()
    }
  }

  def "test create collection"() {
    setup:
    String collectionName = randomCollectionName()
    MongoDatabase db = client.getDatabase(databaseName)

    when:
    db.createCollection(collectionName).subscribe(toSubscriber {})

    then:
    assertTraces(1) {
      trace(1) {
        mongoSpan(it, 0, "create", "{\"create\":\"$collectionName\",\"capped\":\"?\"}")
      }
    }
  }

  def "test create collection with parent"() {
    setup:
    String collectionName = randomCollectionName()
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
        mongoSpan(it, 1, "create", "{\"create\":\"$collectionName\",\"capped\":\"?\"}", false, "some-description", span(0))
      }
    }
  }

  def "test create collection no description"() {
    setup:
    String collectionName = randomCollectionName()
    MongoDatabase db = MongoClients.create("mongodb://${mongoDbContainer.getHost()}:$port").getDatabase(databaseName)

    when:
    db.createCollection(collectionName).subscribe(toSubscriber {})

    then:
    assertTraces(1) {
      trace(1) {
        mongoSpan(it, 0, "create", "{\"create\":\"$collectionName\",\"capped\":\"?\"}", false, databaseName)
      }
    }
  }

  def "test create collection no description with parent"() {
    setup:
    String collectionName = randomCollectionName()
    MongoDatabase db = MongoClients.create("mongodb://${mongoDbContainer.getHost()}:$port").getDatabase(databaseName)

    when:
    runUnderTrace("parent") {
      db.createCollection(collectionName).subscribe(toSubscriber {})
    }

    then:
    assertTraces(1) {
      trace(2) {
        sortSpansByStart()
        basicSpan(it, 0,"parent")
        mongoSpan(it, 1, "create", "{\"create\":\"$collectionName\",\"capped\":\"?\"}", false, databaseName, span(0))
      }
    }
  }

  def "test get collection"() {
    setup:
    String collectionName = randomCollectionName()
    MongoDatabase db = client.getDatabase(databaseName)

    when:
    def count = new CompletableFuture()
    db.getCollection(collectionName).estimatedDocumentCount().subscribe(toSubscriber { count.complete(it) })

    then:
    count.get() == 0
    assertTraces(1) {
      trace(1) {
        mongoSpan(it, 0, "count", "{\"count\":\"$collectionName\"$query}")
      }
    }
  }

  def "command completion retains parent: failed=#failed"() {
    setup:
    def callbackParent = new CompletableFuture()
    def result = new CompletableFuture()
    String command = failed ? "invalidCommand" : "ping"
    def listener = new CommandListener() {
        @Override
        void commandStarted(CommandStartedEvent event) {}

        @Override
        void commandSucceeded(CommandSucceededEvent event) {
          if (event.commandName == command) {
            callbackParent.complete(activeSpan())
          }
        }

        @Override
        void commandFailed(CommandFailedEvent event) {
          if (event.commandName == command) {
            callbackParent.complete(activeSpan())
          }
        }
      }
    client.close()
    client = MongoClients.create(MongoClientSettings.builder()
      .applyConnectionString(new ConnectionString("mongodb://${mongoDbContainer.getHost()}:$port"))
      .applicationName("some-description")
      .applyToSocketSettings {
        it.sendBufferSize(1024)
      }
      .addCommandListener(listener)
      .build())
    // Establish a connection before testing the command's asynchronous write/read handoff.
    setupCollection(randomCollectionName())

    when:
    DDSpan parent = runUnderTrace("parent") {
      // Exceed the socket's send buffer so write completion is asynchronous even on loopback.
      client.getDatabase(databaseName).runCommand(new Document(command, 1).append("comment", "x" * (8 * 1024 * 1024)))
        .subscribe(toSubscriber { result.complete(it) })
      return activeSpan()
    }
    def response = result.get(10, SECONDS)

    then:
    if (failed) {
      assert response instanceof MongoCommandException
    } else {
      assert response.getDouble("ok") == 1.0d
    }
    // Command listeners run before Reactor delivers the result to the subscriber, so Reactor's
    // context propagation cannot hide a lost parent in the driver's transport callbacks.
    callbackParent.get(10, SECONDS) == parent
    TEST_WRITER.waitUntilReported(parent)

    where:
    failed << [false, true]
  }

  def "test get collection with parent"() {
    setup:
    String collectionName = randomCollectionName()
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
        mongoSpan(it, 1, "count", "{\"count\":\"$collectionName\"$query}", false, "some-description", span(0))
      }
    }
  }

  def "test insert"() {
    setup:
    String collectionName = randomCollectionName()
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
        mongoSpan(it, 0, "count", "{\"count\":\"$collectionName\"$query}")
      }
    }
  }

  def "test insert with parent"() {
    setup:
    String collectionName = randomCollectionName()
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
        mongoSpan(it, 1, "insert", "{\"insert\":\"$collectionName\",\"ordered\":true,\"documents\":[]}", false, "some-description", span(0))
        mongoSpan(it, 2, "count", "{\"count\":\"$collectionName\"$query}", false, "some-description", span(0))
      }
    }
  }

  def "test update"() {
    setup:
    String collectionName = randomCollectionName()
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
        mongoSpan(it, 0, "count", "{\"count\":\"$collectionName\"$query}")
      }
    }
  }

  def "test update with parent"() {
    setup:
    String collectionName = randomCollectionName()
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
        mongoSpan(it, 1, "update", "{\"update\":\"$collectionName\",\"ordered\":true,\"updates\":[]}", false, "some-description", span(0))
        mongoSpan(it, 2, "count", "{\"count\":\"$collectionName\"$query}", false, "some-description", span(0))
      }
    }
  }

  def "test delete"() {
    setup:
    String collectionName = randomCollectionName()
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
        mongoSpan(it, 0, "count", "{\"count\":\"$collectionName\"$query}")
      }
    }
  }

  def "test delete with parent"() {
    setup:
    String collectionName = randomCollectionName()
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
        mongoSpan(it, 1, "delete", "{\"delete\":\"$collectionName\",\"ordered\":true,\"deletes\":[]}", false, "some-description", span(0))
        mongoSpan(it, 2, "count", "{\"count\":\"$collectionName\"$query}", false, "some-description", span(0))
      }
    }
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
}

class MongoReactiveClientV0Test extends MongoReactiveClientTest {

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

class MongoReactiveClientV1ForkedTest extends MongoReactiveClientTest {

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

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.MongoNamespace
import com.mongodb.client.model.BulkWriteOptions
import com.mongodb.client.model.Collation
import com.mongodb.client.model.CollationStrength
import com.mongodb.client.model.CountOptions
import com.mongodb.client.model.CreateIndexOptions
import com.mongodb.client.model.DeleteManyModel
import com.mongodb.client.model.DeleteOptions
import com.mongodb.client.model.DropIndexOptions
import com.mongodb.client.model.FindOneAndDeleteOptions
import com.mongodb.client.model.FindOneAndReplaceOptions
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.IndexModel
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.InsertManyOptions
import com.mongodb.client.model.InsertOneModel
import com.mongodb.client.model.InsertOneOptions
import com.mongodb.client.model.RenameCollectionOptions
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.UpdateManyModel
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.result.DeleteResult
import com.mongodb.client.result.UpdateResult
import com.mongodb.event.CommandFailedEvent
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import com.mongodb.event.CommandSucceededEvent
import com.mongodb.internal.build.MongoDriverVersion
import com.mongodb.reactivestreams.client.MongoClient
import com.mongodb.reactivestreams.client.MongoClients
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import datadog.trace.core.DDSpan
import org.bson.BsonDocument
import org.bson.BsonString
import org.bson.Document
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.spockframework.util.VersionNumber
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

abstract class MongoReactiveClientTest extends MongoBaseTest {

  @Shared
  MongoClient client

  final List<Map> usageCommands = new CopyOnWriteArrayList<>()

  def setup() throws Exception {
    client = MongoClients.create(MongoClientSettings.builder()
      .applyConnectionString(new ConnectionString("mongodb://${mongoDbContainer.getHost()}:$port/?appname=some-description"))
      .addCommandListener(new CommandListener() {
        @Override
        void commandStarted(CommandStartedEvent event) {
          MongoReactiveClientTest.this.usageCommands.add([commandName: event.commandName,
            command: BsonDocument.parse(event.command.toJson())])
        }
        @Override
        void commandSucceeded(CommandSucceededEvent event) {}
        @Override
        void commandFailed(CommandFailedEvent event) {}
      }).build())
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

  def "reactive find applies options and preserves parent across getMore"() {
    setup:
    def collection = setupUsageCollection()
    def publisher = collection.find(new Document('rank', new Document('$gte', 1)))
      .sort(new Document('rank', -1)).projection(new Document('rank', 1).append('_id', 0))
      .batchSize(2)
    def result = new CompletableFuture<List<Document>>()
    DDSpan parent

    when:
    runUnderTrace('parent') {
      parent = activeSpan() as DDSpan
      collectUsageResults(publisher, result)
    }

    then:
    def documents = result.get(10, TimeUnit.SECONDS)
    documents*.getInteger('rank') == [5, 4, 3, 2, 1]
    documents.every { it.keySet() == (['rank'] as Set) }
    assertUsageTrace(parent, ['find', 'getMore', 'getMore'])
    activeSpan() == null
  }

  def "reactive first completes empty with one parented find"() {
    setup:
    def collection = setupUsageCollection()
    def result = new CompletableFuture<List<Document>>()
    DDSpan parent

    when:
    runUnderTrace('parent') {
      parent = activeSpan() as DDSpan
      collectUsageResults(collection.find(new Document('rank', 99)).first(), result)
    }

    then:
    result.get(10, TimeUnit.SECONDS).isEmpty()
    assertUsageTrace(parent, ['find'])
    activeSpan() == null
  }

  def "reactive aggregate executes pipeline under parent"() {
    setup:
    def collection = setupUsageCollection()
    def result = new CompletableFuture<List<Document>>()
    def pipeline = [
      new Document('$match', new Document('rank', new Document('$gte', 2))),
      new Document('$sort', new Document('rank', -1)),
      new Document('$limit', 2)
    ]
    DDSpan parent

    when:
    runUnderTrace('parent') {
      parent = activeSpan() as DDSpan
      collectUsageResults(collection.aggregate(pipeline).batchSize(10), result)
    }

    then:
    result.get(10, TimeUnit.SECONDS)*.getInteger('rank') == [5, 4]
    assertUsageTrace(parent, ['aggregate'])
    activeSpan() == null
  }

  def "reactive distinct executes filtered operation under parent"() {
    setup:
    def collection = setupUsageCollection()
    def result = new CompletableFuture<List<Integer>>()
    DDSpan parent

    when:
    runUnderTrace('parent') {
      parent = activeSpan() as DDSpan
      collectUsageResults(collection.distinct('group', Integer)
        .filter(new Document('rank', new Document('$gte', 2))), result)
    }

    then:
    result.get(10, TimeUnit.SECONDS).sort() == [0, 1]
    assertUsageTrace(parent, ['distinct'])
    activeSpan() == null
  }

  def "reactive find callback starts nested count with parent"() {
    setup:
    def collection = setupUsageCollection()
    def result = new CompletableFuture<List<Long>>()
    def callbackParent = new CompletableFuture<Long>()
    DDSpan parent

    when:
    runUnderTrace('parent') {
      parent = activeSpan() as DDSpan
      collection.find().first().subscribe(new Subscriber<Document>() {
          @Override
          void onSubscribe(Subscription subscription) {
            subscription.request(1)
          }

          @Override
          void onNext(Document document) {
            callbackParent.complete(activeSpan()?.spanId)
            collectUsageResults(collection.estimatedDocumentCount(), result)
          }

          @Override
          void onError(Throwable failure) {
            callbackParent.completeExceptionally(failure)
            result.completeExceptionally(failure)
          }

          @Override
          void onComplete() {
          }
        })
    }

    then:
    result.get(10, TimeUnit.SECONDS) == [5L]
    callbackParent.get(10, TimeUnit.SECONDS) == parent.spanId
    assertUsageTrace(parent, ['find', 'count'])
    activeSpan() == null
  }

  def "reactive repeated subscriptions keep independent request parents"() {
    setup:
    def collection = setupUsageCollection()
    def publisher = collection.find(new Document('rank', 1)).first()
    def first = new CompletableFuture<List<Document>>()
    def second = new CompletableFuture<List<Document>>()
    DDSpan firstParent
    DDSpan secondParent

    when:
    runUnderTrace('first-parent') {
      firstParent = activeSpan() as DDSpan
      collectUsageResults(publisher, first)
    }
    runUnderTrace('second-parent') {
      secondParent = activeSpan() as DDSpan
      collectUsageResults(publisher, second)
    }

    then:
    first.get(10, TimeUnit.SECONDS)*.getInteger('rank') == [1]
    second.get(10, TimeUnit.SECONDS)*.getInteger('rank') == [1]
    TEST_WRITER.waitForTraces(2)
    TEST_WRITER.size() == 2
    [firstParent, secondParent].every { parent ->
      def spans = TEST_WRITER.find { trace ->
        trace.any {
          it.spanId == parent.spanId
        }
      }
      assert spans != null
      assert spans.size() == 2
      def command = spans.find { it.spanId != parent.spanId }
      assert command.parentId == parent.spanId
      assert command.traceId == parent.traceId
      assert command.getTag('db.operation').toString() == 'find'
      assert command.operationName.toString() == operation()
      true
    }
    activeSpan() == null
  }

  def "reactive cancellation after first value closes cursor under parent"() {
    setup:
    def collection = setupUsageCollection()
    def received = new CompletableFuture<List<Integer>>()
    def deliveries = new AtomicInteger()
    def callbackParent = new CompletableFuture<Long>()
    DDSpan parent

    when:
    runUnderTrace('parent') {
      parent = activeSpan() as DDSpan
      collection.find().sort(new Document('rank', 1)).batchSize(2).subscribe(new Subscriber<Document>() {
          Subscription subscription
          @Override
          void onSubscribe(Subscription value) {
            subscription = value
            value.request(1)
          }
          @Override
          void onNext(Document value) {
            deliveries.incrementAndGet()
            callbackParent.complete(activeSpan()?.spanId)
            subscription.cancel()
            received.complete([value.getInteger('rank')])
          }
          @Override
          void onError(Throwable failure) {
            received.completeExceptionally(failure)
          }
          @Override
          void onComplete() {
          }
        })
    }

    then:
    received.get(10, TimeUnit.SECONDS) == [1]
    callbackParent.get(10, TimeUnit.SECONDS) == parent.spanId
    new PollingConditions(timeout: 10).eventually {
      assert TEST_WRITER.flatten().any { it.getTag('db.operation')?.toString() == 'killCursors' }
    }
    assertUsageTrace(parent, ['find', 'killCursors'])
    deliveries.get() == 1
    activeSpan() == null
  }

  def "reactive server error retains parent in error callback"() {
    setup:
    def collection = setupUsageCollection()
    def error = new CompletableFuture<Throwable>()
    def callbackParent = new CompletableFuture<Long>()
    DDSpan parent

    when:
    runUnderTrace('parent') {
      parent = activeSpan() as DDSpan
      collection.aggregate([new Document('$notARealStage', new Document())]).subscribe(toSubscriber {
        callbackParent.complete(activeSpan()?.spanId)
        if (it instanceof Throwable) {
          error.complete(it)
        } else {
          error.completeExceptionally(new AssertionError('Expected server aggregate failure'))
        }
      })
    }

    then:
    error.get(10, TimeUnit.SECONDS) instanceof com.mongodb.MongoCommandException
    callbackParent.get(10, TimeUnit.SECONDS) == parent.spanId
    assertUsageTrace(parent, ['aggregate'])
    TEST_WRITER.get(0).find { it.spanId != parent.spanId }.error
    activeSpan() == null
  }

  def "reactive CRUD lifecycle with session=#withSession options=#withOptions pipeline=#pipelineUpdate"() {
    setup:
    def collection = setupUsageCollection()
    def session = withSession ? awaitUsage(client.startSession()).first() : null
    def filter = new Document('rank', new Document('$gte', 3))
    def update = new Document('$set', new Document('changed', true).append('label', 'READY'))
    def updateFilter = withOptions ? new Document('rank', 6) : filter
    def replaceFilter = new Document('rank', withOptions ? 7 : 1)
    def deleteFilter = withOptions ? new Document('label', 'ready') : filter
    def deleteOptions = new DeleteOptions().collation(Collation.builder().locale('en').collationStrength(CollationStrength.SECONDARY).build())
    def mutation = pipelineUpdate ? [update] : update
    def replacement = new Document('rank', withOptions ? 7 : 1).append('replacement', true)
    DDSpan parent

    when:
    runUnderTrace('parent') {
      parent = activeSpan() as DDSpan
      assert awaitUsage(withSession ? collection.countDocuments(session) : collection.countDocuments()) == [5L]
      assert awaitUsage(withSession ? collection.countDocuments(session, filter) : collection.countDocuments(filter)) == [3L]
      assert awaitUsage(withSession ? collection.countDocuments(session, filter, new CountOptions().limit(2)) :
      collection.countDocuments(filter, new CountOptions().limit(2))) == [2L]
      def changed = withSession ? (withOptions ? collection.updateMany(session, updateFilter, mutation, new UpdateOptions().upsert(true)) :
        collection.updateMany(session, updateFilter, mutation)) : (withOptions ? collection.updateMany(updateFilter, mutation, new UpdateOptions().upsert(true)) : collection.updateMany(updateFilter, mutation))
      def updateResult = awaitUsage(changed).first()
      assert updateResult.modifiedCount == (withOptions ? 0 : 3)
      assert (updateResult.upsertedId != null) == withOptions
      def replaced = withSession ? (withOptions ? collection.replaceOne(session, replaceFilter, replacement, new ReplaceOptions().upsert(true)) :
        collection.replaceOne(session, replaceFilter, replacement)) : (withOptions ? collection.replaceOne(replaceFilter, replacement, new ReplaceOptions().upsert(true)) : collection.replaceOne(replaceFilter, replacement))
      def replaceResult = awaitUsage(replaced).first()
      assert replaceResult.modifiedCount == (withOptions ? 0 : 1)
      assert (replaceResult.upsertedId != null) == withOptions
      def deleted = withSession ? (withOptions ? collection.deleteMany(session, deleteFilter, deleteOptions) : collection.deleteMany(session, filter)) :
        (withOptions ? collection.deleteMany(deleteFilter, deleteOptions) : collection.deleteMany(filter))
      assert awaitUsage(deleted).first().deletedCount == (withOptions ? 1 : 3)
      assert awaitUsage(collection.find().sort(new Document('rank', 1)))*.getInteger('rank') == (withOptions ? [1, 2, 3, 4, 5, 7] : [1, 2])
      assert awaitUsage(collection.find(new Document('replacement', true)).first()).first().getInteger('rank') == (withOptions ? 7 : 1)
    }

    then:
    assertUsageTrace(parent, ['aggregate', 'aggregate', 'aggregate', 'update', 'update', 'delete', 'find', 'find'])
    activeSpan() == null

    cleanup:
    session?.close()

    where:
    withSession | withOptions | pipelineUpdate
    false       | false       | false
    false       | true        | false
    true        | false       | false
    true        | true        | false
    false       | false       | true
    false       | true        | true
    true        | false       | true
    true        | true        | true
  }

  def "reactive atomic document lifecycle with session=#withSession options=#withOptions pipeline=#pipelineUpdate"() {
    setup:
    def collection = setupUsageCollection()
    def session = withSession ? awaitUsage(client.startSession()).first() : null
    def filter = new Document('rank', 1)
    def update = new Document('$set', new Document('status', 'updated'))
    def mutation = pipelineUpdate ? [update] : update
    def replacement = new Document('rank', 1).append('status', 'replaced')
    DDSpan parent

    when:
    runUnderTrace('parent') {
      parent = activeSpan() as DDSpan
      def updated = withSession ? (withOptions ? collection.findOneAndUpdate(session, filter, mutation, new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)) :
        collection.findOneAndUpdate(session, filter, mutation)) : (withOptions ? collection.findOneAndUpdate(filter, mutation, new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)) : collection.findOneAndUpdate(filter, mutation))
      assert awaitUsage(updated).first().getString('status') == (withOptions ? 'updated' : null)
      def replaced = withSession ? (withOptions ? collection.findOneAndReplace(session, filter, replacement, new FindOneAndReplaceOptions().returnDocument(ReturnDocument.AFTER)) :
        collection.findOneAndReplace(session, filter, replacement)) : (withOptions ? collection.findOneAndReplace(filter, replacement, new FindOneAndReplaceOptions().returnDocument(ReturnDocument.AFTER)) : collection.findOneAndReplace(filter, replacement))
      assert awaitUsage(replaced).first().getString('status') == (withOptions ? 'replaced' : 'updated')
      def deleted = withSession ? (withOptions ? collection.findOneAndDelete(session, filter, new FindOneAndDeleteOptions().projection(new Document('status', 1).append('_id', 0))) : collection.findOneAndDelete(session, filter)) :
        (withOptions ? collection.findOneAndDelete(filter, new FindOneAndDeleteOptions().projection(new Document('status', 1).append('_id', 0))) : collection.findOneAndDelete(filter))
      def deletedDocument = awaitUsage(deleted).first()
      assert deletedDocument.getString('status') == 'replaced'
      assert deletedDocument.containsKey('rank') == !withOptions
      assert awaitUsage(collection.find(filter).first()).isEmpty()
    }

    then:
    assertUsageTrace(parent, ['findAndModify', 'findAndModify', 'findAndModify', 'find'])
    activeSpan() == null

    cleanup:
    session?.close()

    where:
    withSession | withOptions | pipelineUpdate
    false       | false       | false
    false       | true        | false
    true        | false       | false
    true        | true        | false
    false       | false       | true
    false       | true        | true
    true        | false       | true
    true        | true        | true
  }

  def "reactive mixed bulk write with session=#withSession options=#withOptions"() {
    setup:
    def collection = setupUsageCollection()
    def session = withSession ? awaitUsage(client.startSession()).first() : null
    def requests = [
      new InsertOneModel(new Document('rank', 6)),
      new UpdateManyModel(new Document('group', 0), new Document('$set', new Document('bulk', true))),
      new DeleteManyModel(new Document('rank', new Document('$lte', 2)))
    ]
    usageCommands.clear()
    DDSpan parent

    when:
    runUnderTrace('parent') {
      parent = activeSpan() as DDSpan
      def writes = withSession ? (withOptions ? collection.bulkWrite(session, requests, new BulkWriteOptions().ordered(false)) : collection.bulkWrite(session, requests)) :
        (withOptions ? collection.bulkWrite(requests, new BulkWriteOptions().ordered(false)) : collection.bulkWrite(requests))
      def result = awaitUsage(writes).first()
      assert result.insertedCount == 1
      assert result.modifiedCount == 2
      assert result.deletedCount == 2
      def remaining = awaitUsage(collection.find().sort(new Document('rank', 1)))
      assert remaining*.getInteger('rank') == [3, 4, 5, 6]
      assert remaining.findAll { it.getBoolean('bulk', false) }*.getInteger('rank') == [4]
    }

    then:
    assertUsageTrace(parent, ['insert', 'update', 'delete', 'find'])
    usageCommands.findAll { it.commandName in ['insert', 'update', 'delete'] }.every {
      it.command.getBoolean('ordered').value == !withOptions
    }
    activeSpan() == null

    cleanup:
    session?.close()

    where:
    withSession | withOptions
    false       | false
    false       | true
    true        | false
    true        | true
  }

  def "reactive index and collection lifecycle with session=#withSession options=#withOptions"() {
    setup:
    def collection = setupUsageCollection()
    def session = withSession ? awaitUsage(client.startSession()).first() : null
    def rank = new Document('rank', 1)
    def group = new Document('group', 1)
    def indexes = [new IndexModel(group)]
    def renamedNamespace = new MongoNamespace(databaseName, randomCollectionName())
    if (withOptions) setupCollection(renamedNamespace.collectionName)
    usageCommands.clear()
    DDSpan parent

    when:
    runUnderTrace('parent') {
      parent = activeSpan() as DDSpan
      def created = withSession ? (withOptions ? collection.createIndex(session, rank, new IndexOptions().unique(true)) : collection.createIndex(session, rank)) :
        (withOptions ? collection.createIndex(rank, new IndexOptions().unique(true)) : collection.createIndex(rank))
      assert awaitUsage(created) == ['rank_1']
      def createdMany = withSession ? (withOptions ? collection.createIndexes(session, indexes, new CreateIndexOptions()) : collection.createIndexes(session, indexes)) :
        (withOptions ? collection.createIndexes(indexes, new CreateIndexOptions()) : collection.createIndexes(indexes))
      assert awaitUsage(createdMany) == ['group_1']
      def listing = withSession ? (withOptions ? collection.listIndexes(session, Document) : collection.listIndexes(session)) :
        (withOptions ? collection.listIndexes(Document) : collection.listIndexes())
      def listedIndexes = awaitUsage(listing)
      assert listedIndexes*.getString('name').sort() == ['_id_', 'group_1', 'rank_1']
      assert listedIndexes.find { it.getString('name') == 'rank_1' }.getBoolean('unique', false) == withOptions
      def dropRank = withSession ? (withOptions ? collection.dropIndex(session, 'rank_1', new DropIndexOptions()) : collection.dropIndex(session, 'rank_1')) :
        (withOptions ? collection.dropIndex('rank_1', new DropIndexOptions()) : collection.dropIndex('rank_1'))
      assert awaitUsage(dropRank).isEmpty()
      def dropGroup = withSession ? (withOptions ? collection.dropIndex(session, group, new DropIndexOptions()) : collection.dropIndex(session, group)) :
        (withOptions ? collection.dropIndex(group, new DropIndexOptions()) : collection.dropIndex(group))
      assert awaitUsage(dropGroup).isEmpty()
      assert awaitUsage(collection.listIndexes())*.getString('name') == ['_id_']
      awaitUsage(collection.createIndex(rank))
      def dropAll = withSession ? (withOptions ? collection.dropIndexes(session, new DropIndexOptions()) : collection.dropIndexes(session)) :
        (withOptions ? collection.dropIndexes(new DropIndexOptions()) : collection.dropIndexes())
      assert awaitUsage(dropAll).isEmpty()
      assert awaitUsage(collection.listIndexes())*.getString('name') == ['_id_']
      def rename = withSession ? (withOptions ? collection.renameCollection(session, renamedNamespace, new RenameCollectionOptions().dropTarget(true)) : collection.renameCollection(session, renamedNamespace)) :
        (withOptions ? collection.renameCollection(renamedNamespace, new RenameCollectionOptions().dropTarget(true)) : collection.renameCollection(renamedNamespace))
      assert awaitUsage(rename).isEmpty()
      def renamed = client.getDatabase(databaseName).getCollection(renamedNamespace.collectionName)
      assert awaitUsage(renamed.countDocuments()) == [5L]
      assert awaitUsage(withSession ? renamed.drop(session) : renamed.drop()).isEmpty()
      assert awaitUsage(renamed.countDocuments()) == [0L]
    }

    then:
    assertUsageTrace(parent, [
      'createIndexes',
      'createIndexes',
      'listIndexes',
      'dropIndexes',
      'dropIndexes',
      'listIndexes',
      'createIndexes',
      'dropIndexes',
      'listIndexes',
      'renameCollection',
      'aggregate',
      'drop',
      'aggregate'
    ])
    if (withOptions) {
      assert usageCommands.find { it.commandName == 'renameCollection' }.command.getBoolean('dropTarget').value
    }
    activeSpan() == null

    cleanup:
    session?.close()

    where:
    withSession | withOptions
    false       | false
    false       | true
    true        | false
    true        | true
  }

  def "reactive index timeouts reach server with session=#withSession"() {
    setup:
    def collection = setupUsageCollection()
    def session = withSession ? awaitUsage(client.startSession()).first() : null
    def indexes = [new IndexModel(new Document('rank', 1))]
    usageCommands.clear()
    DDSpan parent

    when:
    runUnderTrace('parent') {
      parent = activeSpan() as DDSpan
      def createOptions = new CreateIndexOptions().maxTime(10, TimeUnit.SECONDS)
      assert awaitUsage(withSession ? collection.createIndexes(session, indexes, createOptions) :
      collection.createIndexes(indexes, createOptions)) == ['rank_1']
      def dropOptions = new DropIndexOptions().maxTime(10, TimeUnit.SECONDS)
      assert awaitUsage(withSession ? collection.dropIndex(session, 'rank_1', dropOptions) :
      collection.dropIndex('rank_1', dropOptions)).isEmpty()
    }

    then:
    assertUsageTrace(parent, ['createIndexes', 'dropIndexes'])
    activeSpan() == null
    // These public options promise a server time limit, so inspect the actual wire commands.
    usageCommands.findAll { it.commandName in ['createIndexes', 'dropIndexes'] }.collect {
      it.command.getNumber('maxTimeMS', null)?.longValue()
    } == [10000L, 10000L]

    cleanup:
    session?.close()

    where:
    withSession << [false, true]
  }

  def "reactive session reads preserve values and parent across typed overloads"() {
    setup:
    def collection = setupUsageCollection()
    def session = awaitUsage(client.startSession()).first()
    def filter = new Document('rank', new Document('$gte', 4))
    def pipeline = [new Document('$match', filter), new Document('$sort', new Document('rank', 1))]
    DDSpan parent

    when:
    runUnderTrace('parent') {
      parent = activeSpan() as DDSpan
      assert awaitUsage(collection.find(session)).size() == 5
      assert awaitUsage(collection.find(session, Document)).size() == 5
      assert awaitUsage(collection.find(session, filter).sort(new Document('rank', 1)))*.getInteger('rank') == [4, 5]
      assert awaitUsage(collection.find(session, filter, Document).sort(new Document('rank', 1)))*.getInteger('rank') == [4, 5]
      assert awaitUsage(collection.aggregate(session, pipeline))*.getInteger('rank') == [4, 5]
      assert awaitUsage(collection.aggregate(session, pipeline, Document))*.getInteger('rank') == [4, 5]
      assert awaitUsage(collection.distinct(session, 'group', Integer)).sort() == [0, 1]
      assert awaitUsage(collection.distinct(session, 'rank', filter, Integer)).sort() == [4, 5]
      assert awaitUsage(collection.distinct('rank', filter, Integer)).sort() == [4, 5]
    }

    then:
    assertUsageTrace(parent, ['find', 'find', 'find', 'find', 'aggregate', 'aggregate', 'distinct', 'distinct', 'distinct'])
    activeSpan() == null

    cleanup:
    session?.close()
  }

  def "reactive session writes with options=#withOptions pipeline=#pipelineUpdate"() {
    setup:
    def collection = setupUsageCollection()
    def session = awaitUsage(client.startSession()).first()
    if (withOptions) {
      DDSpan setupSpan
      runUnderTrace('setup') {
        setupSpan = activeSpan() as DDSpan
        awaitUsage(client.getDatabase(databaseName).runCommand(new Document('collMod', collection.namespace.collectionName)
          .append('validator', new Document('rank', new Document('$lte', 5)))))
      }
      TEST_WRITER.waitUntilReported(setupSpan)
      TEST_WRITER.clear()
    }
    def filter = new Document('rank', withOptions ? 9 : 6)
    def update = new Document('$set', new Document('changed', true).append('label', 'READY'))
    def mutation = pipelineUpdate ? [update] : update
    DDSpan parent

    when:
    runUnderTrace('parent') {
      parent = activeSpan() as DDSpan
      assert awaitUsage(withOptions ? collection.insertOne(session, new Document('rank', 6), new InsertOneOptions().bypassDocumentValidation(true)) :
      collection.insertOne(session, new Document('rank', 6))).size() == 1
      def inserted = [new Document('rank', 7), new Document('rank', 8)]
      assert awaitUsage(withOptions ? collection.insertMany(session, inserted, new InsertManyOptions().bypassDocumentValidation(true)) : collection.insertMany(session, inserted)).size() == 1
      def changed = withOptions ? collection.updateOne(session, filter, mutation, new UpdateOptions().upsert(true).bypassDocumentValidation(true)) : collection.updateOne(session, filter, mutation)
      def changeResult = awaitUsage(changed).first()
      assert changeResult.modifiedCount == (withOptions ? 0 : 1)
      assert (changeResult.upsertedId != null) == withOptions
      assert awaitUsage(collection.find(session, filter).first()).first().getBoolean('changed')
      assert awaitUsage(withOptions ? collection.deleteOne(session, new Document('label', 'ready'), new DeleteOptions().collation(Collation.builder().locale('en').collationStrength(CollationStrength.SECONDARY).build())) : collection.deleteOne(session, filter)).first().deletedCount == 1
      assert awaitUsage(collection.countDocuments(session)) == [withOptions ? 8L : 7L]
    }

    then:
    assertUsageTrace(parent, ['insert', 'insert', 'update', 'find', 'delete', 'aggregate'])
    activeSpan() == null

    cleanup:
    session?.close()

    where:
    withOptions | pipelineUpdate
    false       | false
    true        | false
    false       | true
    true        | true
  }

  protected <T> List<T> awaitUsage(Publisher<T> publisher) {
    def result = new CompletableFuture<List<T>>()
    collectUsageResults(publisher, result)
    return result.get(10, TimeUnit.SECONDS)
  }

  protected MongoCollection<Document> setupUsageCollection() {
    def collection = setupCollection(randomCollectionName())
    DDSpan setupSpan
    def inserted = new CompletableFuture<List<Object>>()
    runUnderTrace('setup') {
      setupSpan = activeSpan() as DDSpan
      collectUsageResults(collection.insertMany((1..5).collect {
        new Document('rank', it).append('group', it % 2)
      }), inserted)
    }
    inserted.get(10, TimeUnit.SECONDS)
    TEST_WRITER.waitUntilReported(setupSpan)
    TEST_WRITER.clear()
    return collection
  }

  protected <T> void collectUsageResults(Publisher<T> publisher, CompletableFuture<List<T>> result) {
    publisher.subscribe(new Subscriber<T>() {
        final List<T> values = []

        @Override
        void onSubscribe(Subscription subscription) {
          subscription.request(Long.MAX_VALUE)
        }

        @Override
        void onNext(T value) {
          values.add(value)
        }

        @Override
        void onError(Throwable failure) {
          result.completeExceptionally(failure)
        }

        @Override
        void onComplete() {
          result.complete(values)
        }
      })
  }

  protected boolean assertUsageTrace(DDSpan parent, List<String> expectedCommands) {
    TEST_WRITER.waitForTraces(1)
    assert TEST_WRITER.size() == 1
    def spans = TEST_WRITER.get(0)
    assert spans.size() == expectedCommands.size() + 1
    assert spans.any { it.spanId == parent.spanId && it.parentId == 0 }
    def commands = spans.findAll { it.spanId != parent.spanId }
    assert commands.collect { it.getTag('db.operation').toString() }.sort() == expectedCommands.sort(false)
    commands.each { command ->
      assert command.parentId == parent.spanId
      assert command.traceId == parent.traceId
      assert command.operationName.toString() == operation()
      assert command.getTag('span.kind') == 'client'
      assert command.getTag('db.type') == dbType()
    }
    return true
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

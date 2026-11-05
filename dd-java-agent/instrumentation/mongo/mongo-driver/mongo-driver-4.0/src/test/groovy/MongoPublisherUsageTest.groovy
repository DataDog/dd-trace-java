import com.mongodb.ConnectionString
import com.mongodb.CursorType
import com.mongodb.MongoClientSettings
import com.mongodb.ReadConcern
import com.mongodb.ReadPreference
import com.mongodb.WriteConcern
import com.mongodb.client.model.Collation
import com.mongodb.client.model.CreateViewOptions
import com.mongodb.client.model.changestream.FullDocument
import com.mongodb.client.model.IndexOptions
import com.mongodb.event.CommandFailedEvent
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import com.mongodb.event.CommandSucceededEvent
import com.mongodb.reactivestreams.client.MongoClient
import com.mongodb.reactivestreams.client.MongoClients
import datadog.trace.core.DDSpan
import org.bson.BsonDocument
import org.bson.Document
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries

abstract class MongoPublisherUsageTest extends MongoBaseTest {
  MongoClient client
  final List<Map<String, Object>> serverCommands = new CopyOnWriteArrayList<>()
  final List<Map<String, Object>> serverReplies = new CopyOnWriteArrayList<>()
  def registry = fromRegistries(MongoClientSettings.defaultCodecRegistry)
  def collation = Collation.builder().locale('en').build()

  def setup() {
    client = createClient(ReadConcern.LOCAL)
  }

  protected MongoClient createClient(ReadConcern readConcern) {
    return MongoClients.create(MongoClientSettings.builder()
      .applyConnectionString(new ConnectionString("mongodb://${mongoDbContainer.host}:$port/?appname=some-description"))
      .addCommandListener(new CommandListener() {
        @Override
        void commandStarted(CommandStartedEvent event) {
          // Event BSON may wrap a reused driver buffer; retain a detached snapshot.
          serverCommands.add([commandName: event.commandName, requestId: event.requestId, command: Document.parse(event.command.toJson())])
        }
        @Override
        void commandSucceeded(CommandSucceededEvent event) {
          serverReplies.add([requestId: event.requestId, response: Document.parse(event.response.toJson())])
        }
        @Override
        void commandFailed(CommandFailedEvent event) {}
      })
      .codecRegistry(registry).readPreference(ReadPreference.primary())
      .readConcern(readConcern).writeConcern(WriteConcern.MAJORITY).build())
  }

  def cleanup() {
    client?.close()
  }

  def 'database catalog publishers decode results with session=#sessionEnabled typed=#typed'() {
    setup:
    def collection = seededCollection()
    def session = collect(client.startSession()).first()
    def resultClass = typed ? BsonDocument : Document

    expect:
    underParent(['listDatabases', 'listDatabases']) {
      def names = collect(sessionEnabled ? client.listDatabaseNames(session) : client.listDatabaseNames())
      assert names.contains(collection.namespace.databaseName)
      def listing = sessionEnabled ? (typed ? client.listDatabases(session, resultClass) : client.listDatabases(session)) :
        (typed ? client.listDatabases(resultClass) : client.listDatabases())
      listing.filter(new Document('name', databaseName)).nameOnly(true).batchSize(10).maxTime(5, TimeUnit.SECONDS)
      // This option was added after the baseline driver; the listing runs on every version.
      if (listing.metaClass.respondsTo(listing, 'authorizedDatabasesOnly', Boolean)) {
        listing.authorizedDatabasesOnly(true)
      }
      def documents = collect(listing.first())
      assert documents.size() == 1
      assert resultClass.isInstance(documents.first())
      assert (typed ? documents.first().getString('name').value : documents.first().getString('name')) == databaseName
      assert documents.first().keySet() == ['name'].toSet()
    }

    cleanup:
    session?.close()

    where:
    sessionEnabled | typed
    false          | false
    false          | true
    true           | false
    true           | true
  }

  def 'database commands views and listings with session=#sessionEnabled typed=#typed'() {
    setup:
    def db = client.getDatabase('publisher-' + UUID.randomUUID())
    def collection = seededCollection(db.name)
    def session = collect(client.startSession()).first()
    def resultClass = typed ? BsonDocument : Document
    def options = new CreateViewOptions().collation(collation)
    def pipeline = [new Document('$match', new Document('rank', new Document('$gte', 3)))]

    expect:
    underParent([
      'create',
      'create',
      'listCollections',
      'listCollections',
      'listCollections',
      'ping',
      'ping',
      'find',
      'dropDatabase'
    ]) {
      collect(sessionEnabled ? db.createView(session, 'ranked', collection.namespace.collectionName, pipeline) : db.createView('ranked', collection.namespace.collectionName, pipeline))
      collect(sessionEnabled ? db.createView(session, 'ranked-en', collection.namespace.collectionName, pipeline, options) : db.createView('ranked-en', collection.namespace.collectionName, pipeline, options))
      def listing = sessionEnabled ? (typed ? db.listCollections(session, resultClass) : db.listCollections(session)) :
        (typed ? db.listCollections(resultClass) : db.listCollections())
      def views = collect(listing.filter(new Document('type', 'view')).maxTime(5, TimeUnit.SECONDS).batchSize(10))
      assert views.size() == 2
      assert views.every { resultClass.isInstance(it) }
      def first = collect(db.listCollections().filter(new Document('name', 'ranked')).first())
      assert first*.getString('name') == ['ranked']
      def names = collect(sessionEnabled ? db.listCollectionNames(session) : db.listCollectionNames())
      assert names.findAll { !it.startsWith('system.') }.toSet() == ['ranked', 'ranked-en', collection.namespace.collectionName].toSet()
      def ping = new Document('ping', 1)
      def plain = sessionEnabled ? (typed ? db.runCommand(session, ping, resultClass) : db.runCommand(session, ping)) :
        (typed ? db.runCommand(ping, resultClass) : db.runCommand(ping))
      def preferred = sessionEnabled ? (typed ? db.runCommand(session, ping, ReadPreference.primary(), resultClass) : db.runCommand(session, ping, ReadPreference.primary())) :
        (typed ? db.runCommand(ping, ReadPreference.primary(), resultClass) : db.runCommand(ping, ReadPreference.primary()))
      [plain, preferred].each {
        def reply = collect(it).first()
        assert resultClass.isInstance(reply)
        assert typed ? reply.getNumber('ok').doubleValue() == 1 : reply.getDouble('ok') == 1
      }
      assert collect(db.getCollection('ranked').find().sort(new Document('rank', 1)))*.getInteger('rank') == [3, 4, 5]
      collect(sessionEnabled ? db.drop(session) : db.drop())
    }

    cleanup:
    session?.close()

    where:
    sessionEnabled | typed
    false          | false
    false          | true
    true           | false
    true           | true
  }

  def 'configured indexed find preserves inherited settings and applies bounds with session=#sessionEnabled'() {
    setup:
    def source = seededCollection()
    def session = collect(client.startSession()).first()
    def inherited = client.getDatabase(databaseName)
    def db = inherited.withCodecRegistry(registry).withReadPreference(ReadPreference.primaryPreferred())
      .withReadConcern(ReadConcern.MAJORITY).withWriteConcern(WriteConcern.ACKNOWLEDGED)
    def collection = db.getCollection(source.namespace.collectionName, Document)
      .withCodecRegistry(registry).withReadPreference(ReadPreference.nearest())
      .withReadConcern(ReadConcern.LOCAL).withWriteConcern(WriteConcern.MAJORITY)

    expect:
    inherited.codecRegistry.get(Document).encoderClass == Document
    inherited.readPreference == ReadPreference.primary()
    inherited.readConcern == ReadConcern.LOCAL
    inherited.writeConcern == WriteConcern.MAJORITY
    db.name == databaseName
    db.codecRegistry.get(Document).encoderClass == Document
    db.readPreference == ReadPreference.primaryPreferred()
    db.readConcern == ReadConcern.MAJORITY
    db.writeConcern == WriteConcern.ACKNOWLEDGED
    collection.documentClass == Document
    collection.codecRegistry.get(Document).encoderClass == Document
    collection.readPreference == ReadPreference.nearest()
    collection.readConcern == ReadConcern.LOCAL
    collection.writeConcern == WriteConcern.MAJORITY
    underParent(['find', 'find']) {
      def find = sessionEnabled ? collection.find(session, Document) : collection.find(Document)
      find.filter(new Document('rank', new Document('$gte', 1)))
        .min(new Document('rank', 1)).max(new Document('rank', 6))
        .skip(1).limit(2).sort(new Document('rank', 1)).projection(new Document('rank', 1).append('_id', 0))
        .collation(collation).comment('bounded-ranked-query').maxTime(5, TimeUnit.SECONDS)
        .noCursorTimeout(false).partial(false)
        .cursorType(CursorType.NonTailable).returnKey(false).showRecordId(true).batchSize(10)
      sessionEnabled ? find.hintString('ranked') : find.hint(new Document('rank', 1))
      def documents = collect(find)
      assert documents*.getInteger('rank') == [2, 3]
      assert documents.every { !it.containsKey('_id') && it.containsKey('$recordId') }
      def command = serverCommand('find')
      assert command.filter == new Document('rank', new Document('$gte', 1))
      assert command.min == new Document('rank', 1) && command.max == new Document('rank', 6)
      assert command.skip == 1 && command.limit == 2
      assert command.projection == new Document('rank', 1).append('_id', 0)
      assert command.sort == new Document('rank', 1)
      assert command.hint == (sessionEnabled ? 'ranked' : new Document('rank', 1))
      assert command.collation == new Document('locale', 'en')
      assert command.comment == 'bounded-ranked-query' && command.maxTimeMS == 5000
      assert command.showRecordId && !command.containsKey('returnKey') && command.batchSize == 10
      assert command.readConcern.level == 'local' && command.'$readPreference'.mode == 'nearest'
      def keys = collect(collection.find().hintString('ranked').collation(collation)
        .sort(new Document('rank', 1)).returnKey(true).showRecordId(false).first())
      assert keys == [new Document('rank', 1)]
      assert serverCommand('find', 1).returnKey
      assert !serverCommand('find', 1).containsKey('showRecordId')
    }

    cleanup:
    session?.close()

    where:
    sessionEnabled << [false, true]
  }

  def 'aggregate and distinct publishers execute configured reads and out writes with session=#sessionEnabled'() {
    setup:
    def collection = seededCollection()
    def db = client.getDatabase(databaseName)
    def session = collect(client.startSession()).first()
    def output = randomCollectionName()
    def readPipeline = [
      new Document('$match', new Document('rank', new Document('$gte', 3))),
      new Document('$sort', new Document('rank', 1))
    ]
    def writePipeline = readPipeline + [new Document('$out', output)]
    def expected = ['aggregate', 'aggregate', 'find', 'distinct', 'distinct', 'aggregate', 'aggregate']

    expect:
    underParent(expected) {
      def read = sessionEnabled ? collection.aggregate(session, readPipeline, Document) : collection.aggregate(readPipeline, Document)
      read.allowDiskUse(true).maxTime(5, TimeUnit.SECONDS)
        .collation(collation).comment('ranked-read').hint(new Document('rank', 1)).batchSize(10)
      assert collect(read.first())*.getInteger('rank') == [3]
      def request = serverCommands.find { it.commandName == 'aggregate' }
      def reply = serverReplies.find { it.requestId == request.requestId }
      assert reply != null
      def cursor = reply.response.cursor
      assert cursor.id instanceof Number
      // first() requests one batch on newer drivers; older drivers fetch all three rows.
      if (cursor.id != 0L) {
        assert cursor.firstBatch*.rank == [3]
        expected.add('killCursors')
      } else {
        assert cursor.firstBatch*.rank == [3, 4, 5]
      }
      def readCommand = serverCommand('aggregate')
      assert readCommand.pipeline == readPipeline
      assert readCommand.allowDiskUse && readCommand.maxTimeMS == 5000
      assert readCommand.collation == new Document('locale', 'en')
      assert readCommand.comment == 'ranked-read' && readCommand.hint == new Document('rank', 1)
      def write = sessionEnabled ? collection.aggregate(session, writePipeline) : collection.aggregate(writePipeline)
      assert collect(write.allowDiskUse(true).bypassDocumentValidation(true).collation(collation)
      .comment('ranked-copy').maxTime(5, TimeUnit.SECONDS).toCollection()).isEmpty()
      def writeCommand = serverCommand('aggregate', 1)
      assert writeCommand.pipeline == writePipeline
      assert writeCommand.allowDiskUse && writeCommand.bypassDocumentValidation
      assert writeCommand.comment == 'ranked-copy' && writeCommand.maxTimeMS == 5000
      assert writeCommand.collation == new Document('locale', 'en')
      assert writeCommand.writeConcern.w == 'majority'
      assert collect(db.getCollection(output).find().sort(new Document('rank', 1)))*.getInteger('rank') == [3, 4, 5]
      def distinct = sessionEnabled ? collection.distinct(session, 'group', Integer) : collection.distinct('group', Integer)
      assert collect(distinct.filter(new Document('rank', new Document('$gte', 2)))
      .maxTime(5, TimeUnit.SECONDS).collation(collation).batchSize(10)).toSet() == [0, 1].toSet()
      def distinctCommand = serverCommand('distinct')
      assert distinctCommand.key == 'group' && distinctCommand.maxTimeMS == 5000
      assert distinctCommand.collation == new Document('locale', 'en')
      assert distinctCommand.query == new Document('rank', new Document('$gte', 2))
      def filtered = new Document('group', 1)
      def first = sessionEnabled ? collection.distinct(session, 'group', filtered, Integer) : collection.distinct('group', filtered, Integer)
      assert collect(first.first()) == [1]
      def admin = client.getDatabase('admin')
      def current = [
        new Document('$currentOp', new Document('allUsers', true)),
        new Document('$match', new Document('command.aggregate', 1)),
        new Document('$limit', 1)
      ]
      def operations = sessionEnabled ? admin.aggregate(session, current, Document) : admin.aggregate(current, Document)
      assert collect(operations).first().getString('op') == 'command'
      def untyped = sessionEnabled ? admin.aggregate(session, current) : admin.aggregate(current)
      assert collect(untyped).first().getString('op') == 'command'
    }

    cleanup:
    session?.close()

    where:
    sessionEnabled << [false, true]
  }

  def 'change stream resumes after first event under parent scope=#scope session=#sessionEnabled typed=#typed'() {
    setup:
    // Change streams require majority read concern, including at the client watch entry point.
    client.close()
    client = createClient(ReadConcern.MAJORITY)
    def collection = seededCollection()
    def db = client.getDatabase(databaseName)
    def session = collect(client.startSession()).first()
    def marker = UUID.randomUUID().toString()
    def pipeline = [new Document('$match', new Document('fullDocument.marker', marker))]
    def startTime
    DDSpan setupParent
    runUnderTrace('setup-events') {
      setupParent = activeSpan() as DDSpan
      collect(db.runCommand(session, new Document('ping', 1)))
      startTime = session.operationTime
      assert startTime != null
      collect(collection.insertMany(session, [
        new Document('_id', marker + '-1').append('marker', marker).append('sequence', 1),
        new Document('_id', marker + '-2').append('marker', marker).append('sequence', 2)
      ]))
    }
    TEST_WRITER.waitUntilReported(setupParent)
    TEST_WRITER.clear()
    def resultClass = typed ? BsonDocument : Document
    def watch = {
      def publisher
      switch (scope) {
        case 'collection':
          publisher = sessionEnabled ? collection.watch(session, pipeline, resultClass) : collection.watch(pipeline, resultClass)
          break
        case 'database':
          publisher = sessionEnabled ? db.watch(session, pipeline, resultClass) : db.watch(pipeline, resultClass)
          break
        case 'client':
          publisher = sessionEnabled ? client.watch(session, pipeline, resultClass) : client.watch(pipeline, resultClass)
          break
        default:
          throw new AssertionError(scope)
      }
      return publisher.fullDocument(FullDocument.UPDATE_LOOKUP).batchSize(1).maxAwaitTime(1, TimeUnit.SECONDS)
    }
    def expected = ['aggregate', 'killCursors', 'aggregate', 'killCursors']

    expect:
    underParent(expected) {
      def first = collect(watch().startAtOperationTime(startTime).first()).first()
      assert first.operationType.value == 'insert'
      assert first.namespace == collection.namespace
      assert first.resumeToken != null
      assert typed ? first.fullDocument.getNumber('sequence').intValue() == 1 : first.fullDocument.getInteger('sequence') == 1
      def resumed = watch()
      // Both resume modes must continue after the already-consumed event.
      scope == 'client' ? resumed.startAfter(first.resumeToken) : resumed.resumeAfter(first.resumeToken)
      def second = collect(resumed.first()).first()
      assert second.operationType.value == 'insert'
      assert second.namespace == collection.namespace
      assert second.resumeToken != first.resumeToken
      assert typed ? second.fullDocument.getNumber('sequence').intValue() == 2 : second.fullDocument.getInteger('sequence') == 2
      def firstCommand = serverCommand('aggregate')
      assert firstCommand.readConcern.level == 'majority'
      assert firstCommand.pipeline[0].'$changeStream'.startAtOperationTime == startTime
      assert firstCommand.pipeline[0].'$changeStream'.fullDocument == 'updateLookup'
      assert firstCommand.pipeline[1] == pipeline.first()
      def resumeCommand = serverCommand('aggregate', 1)
      assert resumeCommand.readConcern.level == 'majority'
      def resumeOption = scope == 'client' ? 'startAfter' : 'resumeAfter'
      assert resumeCommand.pipeline[0].'$changeStream'[resumeOption] == Document.parse(first.resumeToken.toJson())
      assert !resumeCommand.pipeline[0].'$changeStream'.containsKey('startAtOperationTime')
      assert serverCommands.findAll { it.commandName == 'aggregate' }.every { it.command.cursor.batchSize == 1 }
      def getMores = serverCommands.findAll { it.commandName == 'getMore' }
      // Events can arrive in the initial batch or a getMore; every actual command must share the parent.
      if (!getMores.isEmpty()) {
        assert getMores.every { it.command.maxTimeMS == 1000 }
      }
      expected.addAll(getMores.collect { 'getMore' })
    }

    cleanup:
    session?.close()

    where:
    scope        | sessionEnabled | typed
    'collection' | false          | false
    'collection' | true           | true
    'database'   | false          | true
    'database'   | true           | false
    'client'     | false          | false
    'client'     | true           | true
  }

  protected def seededCollection(String name = databaseName) {
    def collection = client.getDatabase(name).getCollection(randomCollectionName())
    DDSpan parent
    runUnderTrace('setup') {
      parent = activeSpan() as DDSpan
      collect(collection.insertMany((1..5).collect { new Document('rank', it).append('group', it % 2) }))
      collect(collection.createIndex(new Document('rank', 1), new IndexOptions().name('ranked').collation(collation)))
    }
    TEST_WRITER.waitUntilReported(parent)
    TEST_WRITER.clear()
    return collection
  }

  protected <T> List<T> collect(Publisher<T> publisher) {
    def result = new CompletableFuture<List<T>>()
    def subscription = new AtomicReference<Subscription>()
    publisher.subscribe(new Subscriber<T>() {
        final List<T> values = []
        @Override
        void onSubscribe(Subscription value) {
          subscription.set(value)
          value.request(Long.MAX_VALUE)
        }
        @Override
        void onNext(T value) {
          values.add(value)
        }
        @Override
        void onError(Throwable error) {
          result.completeExceptionally(error)
        }
        @Override
        void onComplete() {
          result.complete(values)
        }
      })
    try {
      return result.get(10, TimeUnit.SECONDS)
    } finally {
      if (!result.isDone()) {
        subscription.get()?.cancel()
      }
    }
  }

  protected Document serverCommand(String name, int index = 0) {
    return serverCommands.findAll { it.commandName == name }.get(index).command as Document
  }

  protected boolean underParent(List<String> expected, Closure work) {
    assert !expected.isEmpty()
    serverCommands.clear()
    serverReplies.clear()
    DDSpan parent
    runUnderTrace('parent') {
      parent = activeSpan() as DDSpan
      work.call()
    }
    TEST_WRITER.waitUntilReported(parent)
    assert serverCommands*.commandName.sort() == expected.sort(false)
    assert TEST_WRITER.size() == 1
    def spans = TEST_WRITER.first()
    assert spans.size() == expected.size() + 1
    assert spans.any { it.spanId == parent.spanId && it.parentId == 0 }
    def commands = spans.findAll { it.spanId != parent.spanId }
    assert commands.collect { it.getTag('db.operation').toString() }.sort() == expected.sort(false)
    commands.each {
      assert it.parentId == parent.spanId && it.traceId == parent.traceId
      assert it.operationName.toString() == operation()
      assert it.getTag('db.type') == dbType() && it.getTag('span.kind') == 'client'
      assert !it.error
    }
    assert activeSpan() == null
    return true
  }
}

class MongoPublisherUsageV0Test extends MongoPublisherUsageTest {
  @Override int version() {
    0
  }
  @Override String service() {
    V0_SERVICE
  }
  @Override String operation() {
    V0_OPERATION
  }
  @Override String dbType() {
    V0_DB_TYPE
  }
}

class MongoPublisherUsageV1ForkedTest extends MongoPublisherUsageTest {
  @Override int version() {
    1
  }
  @Override String service() {
    V1_SERVICE
  }
  @Override String operation() {
    V1_OPERATION
  }
  @Override String dbType() {
    V1_DB_TYPE
  }
}

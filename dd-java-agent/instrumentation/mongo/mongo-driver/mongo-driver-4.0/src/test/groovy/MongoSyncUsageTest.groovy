import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.ReadConcern
import com.mongodb.ReadPreference
import com.mongodb.WriteConcern
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.model.Collation
import com.mongodb.client.model.CountOptions
import com.mongodb.client.model.CreateCollectionOptions
import com.mongodb.client.model.CreateIndexOptions
import com.mongodb.client.model.CreateViewOptions
import com.mongodb.client.model.IndexModel
import com.mongodb.client.model.IndexOptions
import com.mongodb.event.CommandFailedEvent
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import com.mongodb.event.CommandSucceededEvent
import datadog.trace.core.DDSpan
import org.bson.BsonDocument
import org.bson.Document

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

abstract class MongoSyncUsageTest extends MongoBaseTest {
  MongoClient client
  final List<Map<String, Object>> commands = new CopyOnWriteArrayList<>()
  final def collation = Collation.builder().locale('en').build()

  def setup() {
    client = MongoClients.create(MongoClientSettings.builder()
      .applyConnectionString(new ConnectionString("mongodb://${mongoDbContainer.host}:$port/?appname=some-description"))
      .addCommandListener(new CommandListener() {
        @Override void commandStarted(CommandStartedEvent event) {
          commands.add([name: event.commandName, command: Document.parse(event.command.toJson())])
        }
        @Override void commandSucceeded(CommandSucceededEvent event) {}
        @Override void commandFailed(CommandFailedEvent event) {}
      }).build())
  }

  def cleanup() {
    client?.close()
  }

  def 'sync database catalogs commands and views with session=#withSession typed=#typed'() {
    setup:
    def db = client.getDatabase('sync-' + UUID.randomUUID())
    def session = client.startSession()
    def resultClass = typed ? BsonDocument : Document
    def pipeline = [new Document('$match', new Document('rank', new Document('$gte', 3)))]

    expect:
    underParent([
      'create',
      'create',
      'insert',
      'create',
      'listDatabases',
      'listDatabases',
      'listCollections',
      'listCollections',
      'ping',
      'ping',
      'find',
      'dropDatabase'
    ]) {
      withSession ? db.createCollection(session, 'rows') : db.createCollection('rows')
      def options = new CreateCollectionOptions().capped(true).sizeInBytes(4096)
      withSession ? db.createCollection(session, 'capped', options) : db.createCollection('capped', options)
      db.getCollection('rows').insertMany((1..5).collect { new Document('rank', it) })
      def viewOptions = new CreateViewOptions().collation(collation)
      withSession ? db.createView(session, 'ranked', 'rows', pipeline, viewOptions) : db.createView('ranked', 'rows', pipeline, viewOptions)
      def names = withSession ? client.listDatabaseNames(session) : client.listDatabaseNames()
      def nameCursor = names.batchSize(2).iterator()
      def databaseNames = []
      try {
        while (nameCursor.hasNext()) {
          databaseNames.add(nameCursor.next())
        }
        assert nameCursor.tryNext() == null
        assert nameCursor.serverCursor == null
        assert nameCursor.serverAddress.port == port
      } finally {
        nameCursor.close()
      }
      assert databaseNames.contains(db.name)
      def databases = withSession ? (typed ? client.listDatabases(session, resultClass) : client.listDatabases(session)) :
        (typed ? client.listDatabases(resultClass) : client.listDatabases())
      def listed = databases.filter(new Document('name', db.name)).nameOnly(true).maxTime(5, TimeUnit.SECONDS).first()
      assert resultClass.isInstance(listed)
      assert typed ? listed.getString('name').value == db.name : listed.getString('name') == db.name
      def collections = withSession ? (typed ? db.listCollections(session, resultClass) : db.listCollections(session)) :
        (typed ? db.listCollections(resultClass) : db.listCollections())
      def views = collections.filter(new Document('type', 'view')).batchSize(10).maxTime(5, TimeUnit.SECONDS).into([])
      assert views.size() == 1 && resultClass.isInstance(views.first())
      def collectionNames = withSession ? db.listCollectionNames(session) : db.listCollectionNames()
      assert collectionNames.into([]).findAll { !it.startsWith('system.') }.toSet() == ['rows', 'capped', 'ranked'].toSet()
      def ping = new Document('ping', 1)
      def plain = withSession ? (typed ? db.runCommand(session, ping, resultClass) : db.runCommand(session, ping)) :
        (typed ? db.runCommand(ping, resultClass) : db.runCommand(ping))
      def preferred = withSession ? (typed ? db.runCommand(session, ping, ReadPreference.primary(), resultClass) : db.runCommand(session, ping, ReadPreference.primary())) :
        (typed ? db.runCommand(ping, ReadPreference.primary(), resultClass) : db.runCommand(ping, ReadPreference.primary()))
      [plain, preferred].each { assert typed ? it.getNumber('ok').doubleValue() == 1 : it.getDouble('ok') == 1 }
      assert db.getCollection('ranked').find().sort(new Document('rank', 1)).into([])*.getInteger('rank') == [3, 4, 5]
      withSession ? db.drop(session) : db.drop()
    }

    cleanup:
    session?.close()

    where:
    withSession | typed
    false       | false
    false       | true
    true        | false
    true        | true
  }

  def 'sync configured reads and aggregation writes with session=#withSession'() {
    setup:
    def source = seededCollection()
    def session = client.startSession()
    def db = client.getDatabase(databaseName).withReadConcern(ReadConcern.LOCAL)
      .withReadPreference(ReadPreference.primary()).withWriteConcern(WriteConcern.MAJORITY)
      .withCodecRegistry(MongoClientSettings.defaultCodecRegistry)
    def collection = db.getCollection(source.namespace.collectionName, Document)
      .withReadPreference(ReadPreference.primary()).withReadConcern(ReadConcern.LOCAL)
      .withWriteConcern(WriteConcern.MAJORITY).withCodecRegistry(MongoClientSettings.defaultCodecRegistry)
    def filter = new Document('rank', new Document('$gte', 2))
    def pipeline = [new Document('$match', filter), new Document('$sort', new Document('rank', 1))]
    def output = randomCollectionName()

    expect:
    collection.documentClass == Document
    collection.readConcern == ReadConcern.LOCAL
    collection.writeConcern == WriteConcern.MAJORITY
    underParent(['find', 'find', 'distinct', 'distinct', 'aggregate', 'aggregate', 'find', 'aggregate']) {
      def query = withSession ? collection.find(session, Document) : collection.find(Document)
      def rows = query.filter(filter).sort(new Document('rank', 1)).skip(1).limit(2)
        .projection(new Document('rank', 1).append('_id', 0)).batchSize(10)
        .collation(collation).maxTime(5, TimeUnit.SECONDS).comment('sync-ranked').into([])
      assert rows == [new Document('rank', 3), new Document('rank', 4)]
      assert commands.find { it.name == 'find' }.command.maxTimeMS == 5000
      def first = withSession ? collection.find(session, filter, BsonDocument) : collection.find(filter, BsonDocument)
      assert first.sort(new Document('rank', 1)).first().getInt32('rank').value == 2
      def distinct = withSession ? collection.distinct(session, 'group', Integer) : collection.distinct('group', Integer)
      assert distinct.filter(filter).collation(collation).maxTime(5, TimeUnit.SECONDS).batchSize(10).into([]).toSet() == [0, 1].toSet()
      def distinctFiltered = withSession ? collection.distinct(session, 'group', new Document('group', 1), Integer) :
        collection.distinct('group', new Document('group', 1), Integer)
      assert distinctFiltered.first() == 1
      def aggregate = withSession ? collection.aggregate(session, pipeline, BsonDocument) : collection.aggregate(pipeline, BsonDocument)
      assert aggregate.allowDiskUse(true).maxTime(5, TimeUnit.SECONDS).batchSize(10).collation(collation)
      .comment('sync-aggregate').into([]).collect { it.getInt32('rank').value } == [2, 3, 4, 5]
      def writePipeline = pipeline + [new Document('$out', output)]
      def write = withSession ? collection.aggregate(session, writePipeline) : collection.aggregate(writePipeline)
      write.allowDiskUse(true).bypassDocumentValidation(true).maxTime(5, TimeUnit.SECONDS).toCollection()
      assert db.getCollection(output).find().sort(new Document('rank', 1)).into([])*.getInteger('rank') == [2, 3, 4, 5]
      def countOptions = new CountOptions().maxTime(5, TimeUnit.SECONDS).collation(collation).skip(1).limit(2)
      assert (withSession ? collection.countDocuments(session, filter, countOptions) : collection.countDocuments(filter, countOptions)) == 2
    }

    cleanup:
    session?.close()

    where:
    withSession << [false, true]
  }

  def 'sync index models and typed listing with session=#withSession'() {
    setup:
    def collection = seededCollection()
    def session = client.startSession()
    def key = new Document('rank', 1)
    def models = [new IndexModel(key, new IndexOptions().name('ranked'))]
    def createOptions = new CreateIndexOptions().maxTime(5, TimeUnit.SECONDS)

    expect:
    underParent(['createIndexes', 'listIndexes', 'listIndexes', 'dropIndexes']) {
      def names = withSession ? collection.createIndexes(session, models, createOptions) : collection.createIndexes(models, createOptions)
      assert names == ['ranked']
      assert commands.first().command.maxTimeMS == 5000
      def listing = withSession ? collection.listIndexes(session, BsonDocument) : collection.listIndexes(BsonDocument)
      assert listing.batchSize(10).maxTime(5, TimeUnit.SECONDS).into([]).collect { it.getString('name').value }.toSet() == ['_id_', 'ranked'].toSet()
      def plain = withSession ? collection.listIndexes(session) : collection.listIndexes()
      assert plain.into([])*.getString('name').toSet() == ['_id_', 'ranked'].toSet()
      withSession ? collection.dropIndex(session, key) : collection.dropIndex(key)
    }

    cleanup:
    session?.close()

    where:
    withSession << [false, true]
  }

  protected def seededCollection() {
    def collection = client.getDatabase(databaseName).getCollection(randomCollectionName())
    DDSpan parent
    runUnderTrace('setup') {
      parent = activeSpan() as DDSpan
      collection.insertMany((1..5).collect { new Document('rank', it).append('group', it % 2) })
    }
    TEST_WRITER.waitUntilReported(parent)
    TEST_WRITER.clear()
    return collection
  }

  protected boolean underParent(List<String> expected, Closure work) {
    commands.clear()
    DDSpan parent
    runUnderTrace('parent') {
      parent = activeSpan() as DDSpan
      work.call()
    }
    TEST_WRITER.waitUntilReported(parent)
    assert commands*.name.sort() == expected.sort(false)
    assert TEST_WRITER.size() == 1
    def spans = TEST_WRITER.first()
    assert spans.size() == expected.size() + 1
    assert spans.any { it.spanId == parent.spanId && it.parentId == 0 }
    def children = spans.findAll { it.spanId != parent.spanId }
    assert children.collect { it.getTag('db.operation').toString() }.sort() == expected.sort(false)
    children.each {
      assert it.parentId == parent.spanId && it.traceId == parent.traceId
      assert it.operationName.toString() == operation()
      assert it.getTag('db.type') == dbType() && it.getTag('span.kind') == 'client'
      assert !it.error
    }
    assert activeSpan() == null
    return true
  }
}

class MongoSyncUsageV0Test extends MongoSyncUsageTest {
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

class MongoSyncUsageV1ForkedTest extends MongoSyncUsageTest {
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

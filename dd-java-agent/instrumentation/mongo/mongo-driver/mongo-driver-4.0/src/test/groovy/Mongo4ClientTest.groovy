import com.mongodb.ErrorCategory
import com.mongodb.MongoCommandException
import com.mongodb.MongoWriteException
import com.mongodb.MongoTimeoutException
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.CountOptions
import com.mongodb.client.model.DeleteOneModel
import com.mongodb.client.model.FindOneAndDeleteOptions
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.InsertOneModel
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.internal.build.MongoDriverVersion
import datadog.trace.core.DDSpan
import org.bson.BsonDocument
import org.bson.BsonString
import org.bson.Document
import org.spockframework.util.VersionNumber
import spock.lang.Shared

import static datadog.trace.agent.test.utils.PortUtils.UNUSABLE_PORT
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.DB_CLIENT_HOST_SPLIT_BY_INSTANCE
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

abstract class Mongo4ClientTest extends MongoBaseTest {

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
        mongoSpan(it, 0, "count", "{\"count\":\"$collectionName\"$query}")
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
        mongoSpan(it, 0, "count", "{\"count\":\"$collectionName\"$query}")
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
        mongoSpan(it, 0, "count", "{\"count\":\"$collectionName\"$query}")
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
        mongoSpan(it, 0, "count", "{\"count\":\"$collectionName\"$query}")
      }
    }
  }

  def "test find options and multiple cursor batches preserve parent"() {
    setup:
    def collection = populatedCollection()
    def documents = []

    when:
    runUnderTrace("parent") {
      collection.find(new Document("rank", new Document('$gte', 2)))
        .sort(new Document("rank", -1))
        .projection(new Document("_id", 0).append("rank", 1))
        .batchSize(2).into(documents)
    }

    then:
    documents*.getInteger("rank") == [6, 5, 4, 3, 2]
    documents.every { it.keySet() == (["rank"] as Set) }
    assertCommandParents(["find", "getMore", "getMore"])
  }

  def "test closing an unexhausted cursor preserves cleanup parent"() {
    setup:
    def collection = populatedCollection()
    def first

    when:
    runUnderTrace("parent") {
      def cursor = collection.find().sort(new Document("rank", 1)).batchSize(2).iterator()
      try {
        first = cursor.tryNext()
      } finally {
        cursor.close()
      }
    }

    then:
    first.getInteger("rank") == 1
    assertCommandParents(["find", "killCursors"])
  }

  def "test aggregate and distinct preserve the caller parent"() {
    setup:
    def collection = populatedCollection()
    def documents = []
    def groups = []

    when:
    runUnderTrace("parent") {
      collection.aggregate([
        new Document('$match', new Document("rank", new Document('$gte', 2))),
        new Document('$sort', new Document("rank", -1)),
        new Document('$limit', 2)
      ]).into(documents)
      collection.distinct("group", new Document("rank", new Document('$gte', 2)), String)
        .into(groups)
    }

    then:
    documents*.getInteger("rank") == [6, 5]
    groups.sort() == ["even", "odd"]
    assertCommandParents(["aggregate", "distinct"])
  }

  def "test ordered bulk write traces each command under the parent"() {
    setup:
    def collection = populatedCollection()
    def result

    when:
    runUnderTrace("parent") {
      result = collection.bulkWrite([
        new InsertOneModel<Document>(new Document("_id", 7).append("password", "secret-value")),
        new InsertOneModel<Document>(new Document("_id", 8)),
        new UpdateOneModel<Document>(new Document("_id", 1),
        new Document('$set', new Document("group", "changed"))),
        new DeleteOneModel<Document>(new Document("_id", 2))
      ])
    }

    then:
    result.insertedCount == 2
    result.modifiedCount == 1
    result.deletedCount == 1
    assertCommandParents(["insert", "update", "delete"])
    TEST_WRITER[0].every { !it.resourceName.toString().contains("secret-value") }
  }

  def "test explicit session read and write preserve the caller parent"() {
    setup:
    def collection = populatedCollection()
    def session = client.startSession()
    def document
    def result

    when:
    try {
      runUnderTrace("parent") {
        result = collection.updateOne(session, new Document("_id", 1),
          new Document('$set', new Document("group", "changed")))
        document = collection.find(session, new Document("_id", 1)).first()
      }
    } finally {
      session.close()
    }

    then:
    result.modifiedCount == 1
    document.getString("group") == "changed"
    assertCommandParents(["update", "find"])
  }

  def "test failed aggregate command is an errored child of the caller"() {
    setup:
    def collection = populatedCollection()

    when:
    runUnderTrace("parent") {
      collection.aggregate([new Document('$notAnAggregationStage', 1)]).first()
    }

    then:
    thrown(MongoCommandException)
    assertCommandParents(["aggregate"], true)
  }

  def "test count and findOneAndDelete with explicit session=#withSession"() {
    setup:
    def collection = populatedCollection()
    def session = withSession ? client.startSession() : null
    def counts = []
    def removed = []
    def filter = new Document("group", "even")
    def countOptions = new CountOptions().limit(2)
    def deleteOptions = new FindOneAndDeleteOptions().sort(new Document("rank", -1))
      .projection(new Document("_id", 0).append("rank", 1))

    when:
    try {
      runUnderTrace("parent") {
        if (withSession) {
          counts << collection.countDocuments(session)
          counts << collection.countDocuments(session, filter)
          counts << collection.countDocuments(session, filter, countOptions)
          removed << collection.findOneAndDelete(session, new Document("_id", 1))
          removed << collection.findOneAndDelete(session, filter, deleteOptions)
          counts << collection.countDocuments(session)
        } else {
          counts << collection.countDocuments()
          counts << collection.countDocuments(filter)
          counts << collection.countDocuments(filter, countOptions)
          removed << collection.findOneAndDelete(new Document("_id", 1))
          removed << collection.findOneAndDelete(filter, deleteOptions)
          counts << collection.countDocuments()
        }
      }
    } finally {
      session?.close()
    }

    then:
    counts == [6L, 3L, 2L, 4L]
    removed[0].getInteger("_id") == 1
    removed[1] == new Document("rank", 6)
    assertCommandParents(["aggregate", "aggregate", "aggregate", "findAndModify", "findAndModify", "aggregate"])

    where:
    withSession << [false, true]
  }

  def "test index lifecycle with explicit session=#withSession"() {
    setup:
    def collection = populatedCollection()
    def session = withSession ? client.startSession() : null
    def keys = new Document("rank", 1)
    def options = new IndexOptions().name("unique_rank").unique(true)
    def indexes = []
    def remaining = []
    def indexName

    when:
    try {
      runUnderTrace("parent") {
        if (withSession) {
          indexName = collection.createIndex(session, keys, options)
          collection.listIndexes(session).into(indexes)
          collection.dropIndex(session, indexName)
          collection.listIndexes(session, Document).into(remaining)
        } else {
          indexName = collection.createIndex(keys, options)
          collection.listIndexes().into(indexes)
          collection.dropIndex(indexName)
          collection.listIndexes(Document).into(remaining)
        }
      }
    } finally {
      session?.close()
    }

    then:
    indexName == "unique_rank"
    indexes.find { it.getString("name") == indexName }.getBoolean("unique")
    remaining*.getString("name") == ["_id_"]
    assertCommandParents(["createIndexes", "listIndexes", "dropIndexes", "listIndexes"])

    where:
    withSession << [false, true]
  }

  def "test session aggregate and distinct return decoded results"() {
    setup:
    def collection = populatedCollection()
    def session = client.startSession()
    def documents = []
    def groups = []

    when:
    try {
      runUnderTrace("parent") {
        collection.aggregate(session, [
          new Document('$match', new Document("rank", new Document('$gte', 4))),
          new Document('$sort', new Document("rank", 1))
        ], Document).into(documents)
        collection.distinct(session, "group", String).into(groups)
      }
    } finally {
      session.close()
    }

    then:
    documents*.getInteger("rank") == [4, 5, 6]
    groups.sort() == ["even", "odd"]
    assertCommandParents(["aggregate", "distinct"])
  }

  def "test duplicate key exposes the write error and restores the caller scope"() {
    setup:
    def collection = populatedCollection()
    MongoWriteException failure

    when:
    runUnderTrace("parent") {
      def parent = activeSpan()
      try {
        collection.insertOne(new Document("_id", 1))
      } catch (MongoWriteException error) {
        failure = error
      }
      assert activeSpan() == parent
    }

    then:
    failure != null
    failure.error.code == 11000
    failure.error.category == ErrorCategory.DUPLICATE_KEY
    failure.serverAddress.port == port
    // MongoDB reports writeErrors in a successful command response.
    assertCommandParents(["insert"])
  }

  protected MongoCollection<Document> populatedCollection() {
    DDSpan setupSpan
    def collection = runUnderTrace("setup") {
      setupSpan = activeSpan() as DDSpan
      def result = client.getDatabase(databaseName).getCollection(randomCollectionName())
      result.insertMany((1..6).collect {
        new Document("_id", it).append("rank", it).append("group", it % 2 == 0 ? "even" : "odd")
      })
      result
    }
    TEST_WRITER.waitUntilReported(setupSpan)
    TEST_WRITER.clear()
    collection
  }

  protected void assertCommandParents(List<String> operations, boolean failed = false) {
    TEST_WRITER.waitForTraces(1)
    assert TEST_WRITER.size() == 1
    def spans = TEST_WRITER[0]
    def parent = spans.find { it.operationName.toString() == "parent" }
    assert parent != null
    assert parent.parentId == 0
    def commands = spans.findAll { it.getTag("component")?.toString() == "java-mongo" }
    .sort { it.startTimeNano }
    assert spans.size() == operations.size() + 1
    assert commands.collect { it.getTag("db.operation").toString() } == operations
    commands.each {
      assert it.parentId == parent.spanId
      assert it.traceId == parent.traceId
      assert it.operationName.toString() == operation()
      assert it.serviceName == service()
      assert it.getTag("db.type") == dbType()
      assert it.getTag("db.instance") == "some-description"
      assert it.getTag("span.kind") == "client"
      assert it.isError() == failed
    }
    assert activeSpan() == null
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

class Mongo4ClientV0Test extends Mongo4ClientTest {

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

class Mongo4ClientV1ForkedTest extends Mongo4ClientTest {

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

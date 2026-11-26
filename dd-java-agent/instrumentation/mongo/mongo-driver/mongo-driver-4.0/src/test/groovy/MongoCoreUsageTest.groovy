import com.mongodb.ClientSessionOptions
import com.mongodb.TransactionOptions
import com.mongodb.ReadConcern
import com.mongodb.WriteConcern
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import datadog.trace.core.DDSpan
import org.bson.Document

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

abstract class MongoCoreUsageTest extends MongoBaseTest {
  MongoClient client

  def setup() {
    client = MongoClients.create("mongodb://${mongoDbContainer.host}:$port/?appname=some-description")
  }

  def cleanup() {
    client?.close()
  }

  def 'transaction commit=#commit preserves parent and controls write visibility'() {
    setup:
    def database = client.getDatabase(databaseName)
    def name = randomCollectionName()
    DDSpan setupParent
    runUnderTrace('setup') {
      setupParent = activeSpan() as DDSpan
      database.createCollection(name)
    }
    TEST_WRITER.waitUntilReported(setupParent)
    TEST_WRITER.clear()
    def collection = database.getCollection(name)
    def session = client.startSession(ClientSessionOptions.builder().causallyConsistent(true).build())
    def options = TransactionOptions.builder().readConcern(ReadConcern.SNAPSHOT).writeConcern(WriteConcern.MAJORITY).build()
    DDSpan parent
    Document visible

    when:
    try {
      runUnderTrace('parent') {
        parent = activeSpan() as DDSpan
        session.startTransaction(options)
        collection.insertOne(session, new Document('_id', 1).append('value', 'transaction-value'))
        assert collection.find(session, new Document('_id', 1)).first().getString('value') == 'transaction-value'
        assert activeSpan() == parent
        if (commit) {
          session.commitTransaction()
        } else {
          session.abortTransaction()
        }
        assert !session.hasActiveTransaction()
        visible = collection.find(new Document('_id', 1)).first()
        assert activeSpan() == parent
      }
    } finally {
      session.close()
    }
    TEST_WRITER.waitUntilReported(parent)

    then:
    commit ? visible?.getString('value') == 'transaction-value' : visible == null
    TEST_WRITER.size() == 1
    def spans = TEST_WRITER.first()
    def children = spans.findAll { it.spanId != parent.spanId }.sort { it.startTimeNano }
    spans.size() == 5
    children.collect { it.getTag('db.operation').toString() } == ['insert', 'find', commit ? 'commitTransaction' : 'abortTransaction', 'find']
    children.every {
      it.parentId == parent.spanId && it.traceId == parent.traceId &&
        it.operationName.toString() == operation() && it.serviceName == service() &&
        it.getTag('db.type') == dbType() && it.getTag('span.kind') == 'client' && !it.error
    }
    activeSpan() == null

    where:
    commit << [false, true]
  }
}

class MongoCoreUsageV0Test extends MongoCoreUsageTest {
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

class MongoCoreUsageV1ForkedTest extends MongoCoreUsageTest {
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

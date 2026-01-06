import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.datanucleus.api.jdo.JDOPersistenceManager

import javax.jdo.JDOHelper
import javax.jdo.PersistenceManager
import javax.jdo.PersistenceManagerFactory

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

/**
 * Tests datanucleus from the persistence manager.  These eventually call the instrumented classes
 */
class DatanucleusTest extends InstrumentationSpecification {
  PersistenceManagerFactory factory
  PersistenceManager persistenceManager

  def setup() {
    runUnderTrace("setup") {
      factory = JDOHelper.getPersistenceManagerFactory("testPersistenceUnit")
      persistenceManager = factory.getPersistenceManager()

      // Ensure that the schema and tables are created outside of the tests
      def executionContext = ((JDOPersistenceManager) persistenceManager).getExecutionContext()
      executionContext.getStoreManager().manageClasses(executionContext.getClassLoaderResolver(), Value.name)
    }
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.clear()
  }

  def cleanup() {
    runUnderTrace("cleanup") {
      factory?.close()
    }
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.clear()
  }

  def "persist value"() {
    setup:
    def value = new Value("some name")

    when:
    persistenceManager.makePersistent(value)

    then:
    assertTraces(1) {
      trace(2) {
        datanucleusSpan(it, "datanucleus.persistObject", "Value")
        h2Span(it, "INSERT INTO \"VALUE\"")
      }
    }
  }

  def "persist multiple values"() {
    setup:
    def value = new Value("some name")
    def value2 = new Value("some other name")

    when:
    persistenceManager.makePersistentAll(value, value2)

    then:
    assertTraces(1) {
      trace(3) {
        datanucleusSpan(it, "datanucleus.persistObjects", "datanucleus.persistObjects")
        h2Span(it, "INSERT INTO \"VALUE\"")
        h2Span(it, "INSERT INTO \"VALUE\"", span(0))
      }
    }
  }

  def "delete value"() {
    setup:
    def value = new Value("some name")

    when:
    persistenceManager.makePersistent(value)
    persistenceManager.deletePersistent(value)

    then:
    assertTraces(2) {
      trace(2) {
        datanucleusSpan(it, "datanucleus.persistObject", "Value")
        h2Span(it, "INSERT INTO \"VALUE\"")
      }
      trace(2) {
        datanucleusSpan(it, "datanucleus.deleteObject", "Value")
        h2Span(it, "DELETE FROM \"VALUE\"")
      }
    }
  }

  def "deleteAll values"() {
    setup:
    def value = new Value("some name")
    def value2 = new Value("some other name")

    when:
    persistenceManager.makePersistentAll(value, value2)
    persistenceManager.deletePersistentAll(value, value2)

    then:
    assertTraces(2) {
      trace(3) {
        datanucleusSpan(it, "datanucleus.persistObjects", "datanucleus.persistObjects")
        h2Span(it, "INSERT INTO \"VALUE\"")
        h2Span(it, "INSERT INTO \"VALUE\"", span(0))
      }
      trace(3) {
        datanucleusSpan(it, "datanucleus.deleteObjects", "datanucleus.deleteObjects")
        h2Span(it, "DELETE FROM \"VALUE\"")
        h2Span(it, "DELETE FROM \"VALUE\"", span(0))
      }
    }
  }

  def "retrieve by object id"() {
    setup:
    def value = new Value("some name")

    when:
    persistenceManager.makePersistent(value)
    def id = persistenceManager.getObjectId(value)
    Value retrievedValue = persistenceManager.getObjectById(id)

    then:
    retrievedValue.name == value.name

    assertTraces(2) {
      trace(2) {
        datanucleusSpan(it, "datanucleus.persistObject", "Value")
        h2Span(it, "INSERT INTO \"VALUE\"")
      }
      trace(2) {
        datanucleusSpan(it, "datanucleus.findObject", "Value")
        h2Span(it, "SELECT ")
      }
    }
  }

  def "retrieve by id"() {
    setup:
    def value = new Value("some name")

    when:
    persistenceManager.makePersistent(value)
    Value retrievedValue = persistenceManager.getObjectById(Value, value.id)

    then:
    retrievedValue.name == value.name

    assertTraces(2) {
      trace(2) {
        datanucleusSpan(it, "datanucleus.persistObject", "Value")
        h2Span(it, "INSERT INTO \"VALUE\"")
      }
      trace(2) {
        datanucleusSpan(it, "datanucleus.findObject", "Value")
        h2Span(it, "SELECT ")
      }
    }
  }

  def "retrieve multiple by object id"() {
    setup:
    def value = new Value("some name")
    def value2 = new Value("some other name")

    when:
    persistenceManager.makePersistentAll(value, value2)
    def id = persistenceManager.getObjectId(value)
    def id2 = persistenceManager.getObjectId(value2)

    def result = persistenceManager.getObjectsById(id, id2)

    then:
    result.size() == 2
    ((Value) result[0]).name == value.name || ((Value) result[1]).name == value.name
    ((Value) result[0]).name == value2.name || ((Value) result[1]).name == value2.name

    assertTraces(2) {
      trace(3) {
        datanucleusSpan(it, "datanucleus.persistObjects", "datanucleus.persistObjects")
        h2Span(it, "INSERT INTO \"VALUE\"")
        h2Span(it, "INSERT INTO \"VALUE\"", span(0))
      }
      trace(2) {
        datanucleusSpan(it, "datanucleus.findObjects", "datanucleus.findObjects")
        h2Span(it, "SELECT ")
      }
    }
  }

  def "refresh object"() {
    setup:
    def value = new Value("some name")

    when:
    persistenceManager.makePersistent(value)
    persistenceManager.refresh(value)

    then:
    assertTraces(2) {
      trace(2) {
        datanucleusSpan(it, "datanucleus.persistObject", "Value")
        h2Span(it, "INSERT INTO \"VALUE\"")
      }
      trace(2) {
        datanucleusSpan(it, "datanucleus.refreshObject", "Value")
        h2Span(it, "SELECT ")
      }
    }
  }

  def "refreshAll objects"() {
    setup:
    def value = new Value("some name")
    def value2 = new Value("some other name")

    when:
    persistenceManager.makePersistentAll(value, value2)
    persistenceManager.refreshAll()

    then:
    assertTraces(2) {
      trace(3) {
        datanucleusSpan(it, "datanucleus.persistObjects", "datanucleus.persistObjects")
        h2Span(it, "INSERT INTO \"VALUE\"")
        h2Span(it, "INSERT INTO \"VALUE\"", span(0))
      }
      trace(3) {
        datanucleusSpan(it, "datanucleus.refreshAllObjects", "datanucleus.refreshAllObjects")
        h2Span(it, "SELECT ")
        h2Span(it, "SELECT ", span(0))
      }
    }
  }

  def "refreshAll with collection"() {
    setup:
    def value = new Value("some name")
    def value2 = new Value("some other name")

    when:
    persistenceManager.makePersistentAll(value, value2)
    persistenceManager.refreshAll(value, value2)

    then:
    assertTraces(3) {
      trace(3) {
        datanucleusSpan(it, "datanucleus.persistObjects", "datanucleus.persistObjects")
        h2Span(it, "INSERT INTO \"VALUE\"")
        h2Span(it, "INSERT INTO \"VALUE\"", span(0))
      }
      // RefreshAll with a collection just refresh twice
      trace(2) {
        datanucleusSpan(it, "datanucleus.refreshObject", "Value")
        h2Span(it, "SELECT ")
      }
      trace(2) {
        datanucleusSpan(it, "datanucleus.refreshObject", "Value")
        h2Span(it, "SELECT ")
      }
    }
  }

  def "query object"() {
    setup:
    def value = new Value("some name")
    def queryString = "SELECT FROM " + Value.name

    when:
    persistenceManager.makePersistent(value)
    def query = persistenceManager.newQuery(queryString)
    query.setClass(Value)
    query.execute()

    then:
    assertTraces(2) {
      trace(2) {
        datanucleusSpan(it, "datanucleus.persistObject", "Value")
        h2Span(it, "INSERT INTO \"VALUE\"")
      }
      trace(2) {
        datanucleusSpan(it, "datanucleus.query.execute", "Value")
        h2Span(it, "SELECT ")
      }
    }
  }


  def "delete from query"() {
    setup:
    def value = new Value("some name")
    def queryString = "SELECT FROM " + Value.name

    when:
    persistenceManager.makePersistent(value)
    def query = persistenceManager.newQuery(queryString)
    query.setClass(Value)
    query.deletePersistentAll()

    then:
    assertTraces(2) {
      trace(2) {
        datanucleusSpan(it, "datanucleus.persistObject", "Value")
        h2Span(it, "INSERT INTO \"VALUE\"")
      }
      trace(4) {
        datanucleusSpan(it, "datanucleus.query.delete", "Value")

        datanucleusSpan(it, "datanucleus.deleteObjects", "datanucleus.deleteObjects", span(0))
        h2Span(it, "DELETE FROM ", span(1))
        h2Span(it, "SELECT ", span(0))
      }
    }
  }

  def "update in transaction"() {
    setup:
    def value = new Value("some name")

    when:
    persistenceManager.makePersistent(value)

    def transaction = persistenceManager.currentTransaction()
    transaction.begin()
    value.name = "some other name"
    persistenceManager.makePersistent(value)
    transaction.commit()

    then:
    assertTraces(3) {
      trace(2) {
        datanucleusSpan(it, "datanucleus.persistObject", "Value")
        h2Span(it, "INSERT INTO \"VALUE\"")
      }
      trace(1) {
        datanucleusSpan(it, "datanucleus.persistObject", "Value")
      }
      trace(2) {
        datanucleusSpan(it, "datanucleus.transaction.commit", "datanucleus.transaction.commit")
        h2Span(it, "UPDATE \"VALUE\"", span(0))
      }
    }
  }

  def "rollback transaction"() {
    setup:
    def value = new Value("some name")

    when:
    persistenceManager.makePersistent(value)

    def transaction = persistenceManager.currentTransaction()
    transaction.begin()
    value.name = "some other name"
    persistenceManager.makePersistent(value)
    transaction.rollback()

    then:
    assertTraces(3) {
      trace(2) {
        datanucleusSpan(it, "datanucleus.persistObject", "Value")
        h2Span(it, "INSERT INTO \"VALUE\"")
      }
      trace(1) {
        datanucleusSpan(it, "datanucleus.persistObject", "Value")
      }
      trace(1) {
        datanucleusSpan(it, "datanucleus.transaction.rollback", "datanucleus.transaction.rollback")
      }
    }
    value.name == "some name"
  }

  void datanucleusSpan(TraceAssert trace, String expectedOperationName, String expectedResourceName, Object spanParent = null) {
    trace.span {
      serviceName "datanucleus"
      operationName expectedOperationName
      resourceName expectedResourceName
      spanType DDSpanTypes.DATANUCLEUS

      if (spanParent != null) {
        childOf((DDSpan) spanParent)
      } else {
        parent()
      }

      tags {
        "$Tags.COMPONENT" "java-datanucleus"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        defaultTags()
      }
    }
  }

  void h2Span(TraceAssert trace, String queryStart, Object parent = null) {
    trace.span {
      serviceName "h2"
      operationName "h2.query"
      resourceName { it.startsWith(queryStart) }
      spanType DDSpanTypes.SQL

      if (parent != null) {
        childOf(parent as DDSpan)
      } else {
        childOfPrevious()
      }

      tags {
        "$Tags.COMPONENT" "java-jdbc-prepared_statement"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.DB_TYPE" "h2"
        "$Tags.DB_INSTANCE" "nucleus"
        "$Tags.DB_USER" "sa"
        "$Tags.DB_OPERATION" queryStart.split(" ")[0]
        defaultTags()
      }
    }
  }
}

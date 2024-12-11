import com.datadog.iast.taint.TaintedObject
import com.datadog.iast.test.IastAgentTestRunner
import datadog.trace.api.iast.SourceTypes
import foo.bar.IastInstrumentedConnection
import spock.lang.Shared

import javax.sql.DataSource
import java.sql.Connection

class IastResultSetInstrumentationTest extends IastAgentTestRunner {
  @Shared
  DataSource dataSource = new org.apache.tomcat.jdbc.pool.DataSource(
  url: 'jdbc:h2:mem:iastUnitTest',
  driverClassName: 'org.h2.Driver',
  password: '',
  maxActive: 1,
  )

  private Connection connection
  Connection getConnection() {
    connection = (connection ?: dataSource.connection)
  }

  void cleanup() {
    connection?.close()
  }

  void setupSpec() {
    dataSource.connection.withCloseable {
      it.createStatement().withCloseable {stmt ->
        stmt.executeUpdate('CREATE TABLE TEST (id integer NOT NULL, name VARCHAR NOT NULL)')
        stmt.executeUpdate('INSERT INTO TEST VALUES(42, \'foo\')')
        stmt.executeUpdate('INSERT INTO TEST VALUES(43, \'bar\')')
      }
    }
  }

  void cleanupSpec() {
    dataSource.close(true)
  }

  void 'returned string is tainted with source values as sql table'() {
    when:
    def valueRead
    TaintedObject taintedObject
    runUnderIastTrace {
      String sql = 'SELECT name FROM TEST LIMIT 1'
      Connection constWrapper = new IastInstrumentedConnection(conn: connection)

      constWrapper.prepareStatement(sql).withCloseable { stmt ->
        stmt.executeQuery().withCloseable { rs ->
          if (rs.next()) {
            valueRead = rs.getString(1)
          }
        }
      }

      taintedObject = localTaintedObjects.get(valueRead)
    }

    then:
    valueRead == "foo"
    taintedObject != null
    with(taintedObject) {
      with(ranges[0]) {
        source.origin == SourceTypes.SQL_TABLE
        source.value == valueRead
      }
    }
  }

  void 'returned string is tainted with source values as sql table but second value is not tainted'() {
    when:
    def firstValue
    def secondValue
    TaintedObject firstTaintedObject
    TaintedObject secondTaintedObject
    boolean isFirst = true
    runUnderIastTrace {
      String sql = 'SELECT name FROM TEST LIMIT 2'
      Connection constWrapper = new IastInstrumentedConnection(conn: connection)

      constWrapper.prepareStatement(sql).withCloseable { stmt ->
        stmt.executeQuery().withCloseable { rs ->
          while (rs.next()) {
            if (isFirst) {
              firstValue = rs.getString("name")
              isFirst = false
            } else {
              secondValue = rs.getString("name")
            }
          }
        }
      }

      firstTaintedObject = localTaintedObjects.get(firstValue)
      secondTaintedObject = localTaintedObjects.get(secondValue)
    }

    then:
    firstValue == "foo"
    firstTaintedObject != null
    with(firstTaintedObject) {
      with(ranges[0]) {
        source.origin == SourceTypes.SQL_TABLE
        source.value == firstValue
      }
    }
    secondValue == "bar"
    secondTaintedObject == null
  }

  void 'when returned value is not a string does not taint the result' () {
    when:
    def valueRead
    TaintedObject taintedObject
    runUnderIastTrace {
      String sql = 'SELECT id FROM TEST LIMIT 1'
      Connection constWrapper = new IastInstrumentedConnection(conn: connection)

      constWrapper.prepareStatement(sql).withCloseable { stmt ->
        stmt.executeQuery().withCloseable { rs ->
          if (rs.next()) {
            valueRead = rs.getInt(1)
          }
        }
      }

      taintedObject = localTaintedObjects.get(valueRead)
    }

    then:
    valueRead == 42
    taintedObject == null
  }
}

import com.datadog.iast.IastAgentTestRunner
import com.datadog.iast.model.VulnerabilityBatch
import datadog.trace.core.DDSpan
import foo.bar.IastJdbcCallSites
import spock.lang.Shared

import javax.sql.DataSource
import java.sql.Connection

class IastJDBCTest extends IastAgentTestRunner {
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
        stmt.executeUpdate('CREATE TABLE TEST (id integer NOT NULL)')
        stmt.executeUpdate('INSERT INTO TEST VALUES(42)')
      }
    }
  }

  void cleanupSpec() {
    dataSource.close(true)
  }

  void 'vulnerability is reported on connection method when query string is tainted'() {
    when:
    def valueRead
    DDSpan span = runUnderIastTrace {
      String taintedString = 'SELECT id FROM TEST LIMIT 1'
      taintedObjects.taintInputString(taintedString, EMPTY_SOURCE)

      IastJdbcCallSites.prepareStatement(connection, taintedString).withCloseable { stmt ->
        stmt.executeQuery().withCloseable { rs ->
          if (rs.next()) {
            valueRead = rs.getInt(1)
          }
        }
      }
    }
    TEST_WRITER.waitUntilReported(span)
    VulnerabilityBatch iastReport = span.getTag('_dd.iast.json')

    then:
    valueRead == 42
    iastReport != null
    with(iastReport.vulnerabilities[0].evidence) {
      value == 'SELECT id FROM TEST LIMIT 1'
      ranges.length == 1
      with(ranges[0]) {
        start == 0
        length == 27
      }
    }
  }

  void 'no vulnerability is reported on connection method if the string is not tainted'() {
    when:
    def valueRead
    DDSpan span = runUnderIastTrace {
      String taintedString = 'SELECT id FROM TEST LIMIT 1'
      IastJdbcCallSites.prepareStatement(connection, taintedString).withCloseable { stmt ->
        stmt.executeQuery().withCloseable { rs ->
          if (rs.next()) {
            valueRead = rs.getInt(1)
          }
        }
      }
    }
    TEST_WRITER.waitUntilReported(span)

    then:
    valueRead == 42
    span.getTag('_dd.iast.json') == null
  }

  void 'vulnerability is reported on statement method when query string is tainted'() {
    when:
    def valueRead
    DDSpan span = runUnderIastTrace {
      String taintedString = 'SELECT id FROM TEST LIMIT 1'
      taintedObjects.taintInputString(taintedString, EMPTY_SOURCE)

      connection.createStatement().withCloseable { stmt ->
        IastJdbcCallSites.executeQuery(stmt, taintedString).withCloseable { rs ->
          if (rs.next()) {
            valueRead = rs.getInt(1)
          }
        }
      }
    }
    TEST_WRITER.waitUntilReported(span)
    VulnerabilityBatch iastReport = span.getTag('_dd.iast.json')

    then:
    valueRead == 42
    iastReport != null
    with(iastReport.vulnerabilities[0].evidence) {
      value == 'SELECT id FROM TEST LIMIT 1'
    }
  }
}

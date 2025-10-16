import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.instrumentation.jdbc.JDBCDecorator
import java.sql.Connection
import java.sql.SQLException
import test.TestConnection
import test.TestDatabaseMetaData

class JDBCDecoratorTest extends InstrumentationSpecification {
  def "test dbInfo fetching"() {
    Connection connection = new TestConnection(false) {
        @Override
        Properties getClientInfo() throws SQLException {
          var prop = new Properties()
          prop.setProperty("warehouse", "hello")
          return prop
        }
      }
    def metadata = new TestDatabaseMetaData()
    metadata.setURL("jdbc:testdb://myhost:9999/testdatabase")
    connection.setMetaData(metadata)

    when:
    def dbInfo = JDBCDecorator.parseDBInfoFromConnection(connection)

    then:
    dbInfo != null
    assert dbInfo.type == "testdb"
    assert dbInfo.db == "testdatabase"
    assert dbInfo.host == "myhost"
    assert dbInfo.port == 9999
    assert dbInfo.warehouse == "hello"
  }
}

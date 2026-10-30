import static datadog.trace.instrumentation.jdbc.mysql.ConnectorJIntrospection.cachePrepStmts
import static datadog.trace.instrumentation.jdbc.mysql.ConnectorJIntrospection.prepStmtCacheSize

class MySqlStatementCacheDisabledTest extends MySqlContainerSpecification {

  def "connection keeps the driver defaults when the flag is off"() {
    when:
    def conn = connect(url())

    then:
    !cachePrepStmts(conn)
    prepStmtCacheSize(conn) == 25

    cleanup:
    conn?.close()
  }
}

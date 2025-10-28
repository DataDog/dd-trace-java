import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.h2.Driver
import test.TestConnection
import test.TestPreparedStatement
import test.WrappedConnection
import test.WrappedPreparedStatement

/**
 * This tests all combinations of wrapped/unwrapped connections and prepared statements
 * H2 classes are called out because the don't implement the Wrapper interface.  They are based an older spec leading to AbstractMethodError
 */
class JDBCWrappedInterfacesTest extends InstrumentationSpecification {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.trace.jdbc.prepared.statement.class.name", "test.TestPreparedStatement")
    injectSysConfig("dd.trace.jdbc.connection.class.name", "test.TestConnection")
  }

  static query = "SELECT 1"
  static obfuscatedQuery = "SELECT ?"

  def "prepare on unwrapped conn, execute unwrapped stmt"() {
    setup:
    def connection = new TestConnection(false)

    when:
    runUnderTrace("parent") {
      def statement = connection.prepareStatement(query) as TestPreparedStatement
      statement.execute()
    }

    then:
    assertDBTraces()
  }

  def "prepare on unwrapped conn, assign wrapped conn to statement, execute unwrapped stmt"() {
    setup:
    def connection = new TestConnection(false)

    when:
    runUnderTrace("parent") {
      def statement = connection.prepareStatement(query) as TestPreparedStatement
      statement.connection = new WrappedConnection(connection)
      statement.execute()
    }

    then:
    assertDBTraces()
  }

  def "prepare on unwrapped conn, assign wrapped conn to statement, execute wrapped stmt"() {
    setup:
    def connection = new TestConnection(false)

    when:
    runUnderTrace("parent") {
      def statement = connection.prepareStatement(query) as TestPreparedStatement
      statement.connection = new WrappedConnection(connection)

      def wrappedStatement = new WrappedPreparedStatement(statement)
      wrappedStatement.execute()
    }

    then:
    assertDBTraces()
  }

  def "prepare on unwrapped conn, execute wrapped stmt"() {
    setup:
    def connection = new TestConnection(false)

    when:
    runUnderTrace("parent") {
      def statement = connection.prepareStatement(query) as TestPreparedStatement

      def wrappedStatement = new WrappedPreparedStatement(statement)
      wrappedStatement.execute()
    }

    then:
    assertDBTraces()
  }

  def "prepare on wrapped conn, assign unwrapped conn, execute unwrapped stmt"() {
    setup:
    def connection = new TestConnection(false)
    def wrappedConnection = new WrappedConnection(connection)

    when:
    runUnderTrace("parent") {
      def statement = wrappedConnection.prepareStatement(query) as TestPreparedStatement
      statement.connection = connection
      statement.execute()
    }

    then:
    assertDBTraces()
  }

  def "prepare on wrapped conn, execute wrapped stmt"() {
    setup:
    def connection = new TestConnection(false)
    def wrappedConnection = new WrappedConnection(connection)

    when:
    runUnderTrace("parent") {
      def statement = wrappedConnection.prepareStatement(query) as TestPreparedStatement

      def wrappedStatement = new WrappedPreparedStatement(statement)
      wrappedStatement.execute()
    }

    then:
    assertDBTraces()
  }

  def "prepare on wrapped conn, assign unwrapped conn to statement, execute wrapped"() {
    setup:
    def connection = new TestConnection(false)
    def wrappedConnection = new WrappedConnection(connection)

    when:
    runUnderTrace("parent") {
      def statement = wrappedConnection.prepareStatement(query) as TestPreparedStatement
      statement.connection = connection

      def wrappedStatement = new WrappedPreparedStatement(statement)
      wrappedStatement.execute()
    }

    then:
    assertDBTraces()
  }

  def "prepare on wrapped conn, execute unwrapped"() {
    setup:
    def connection = new TestConnection(false)
    def wrappedConnection = new WrappedConnection(connection)

    when:
    runUnderTrace("parent") {
      def statement = wrappedConnection.prepareStatement(query) as TestPreparedStatement

      statement.execute()
    }

    then:
    assertDBTraces()
  }

  def "h2 prepare on unwrapped conn, execute unwrapped stmt"() {
    setup:
    def connection = runUnderTrace("initialization") {
      new Driver().connect("jdbc:h2:mem:", null)
    }
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.clear()

    when:
    runUnderTrace("parent") {
      def statement = connection.prepareStatement(query)
      statement.execute()
    }

    then:
    assertDBTraces("h2")

    cleanup:
    connection?.close()
  }

  def "h2 prepare on unwrapped conn, execute wrapped stmt"() {
    setup:
    def connection = runUnderTrace("initialization") {
      new Driver().connect("jdbc:h2:mem:", null)
    }
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.clear()

    when:
    runUnderTrace("parent") {
      def statement = connection.prepareStatement(query)
      def wrappedStatement = new WrappedPreparedStatement(statement)
      wrappedStatement.execute()
    }

    then:
    assertDBTraces("h2")

    cleanup:
    connection?.close()
  }

  def "h2 prepare on wrapped conn, execute unwrapped stmt"() {
    setup:
    def connection = runUnderTrace("initialization") {
      new Driver().connect("jdbc:h2:mem:", null)
    }
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.clear()

    when:
    runUnderTrace("parent") {
      def statement = new WrappedConnection(connection).prepareStatement(query)
      statement.execute()
    }

    then:
    assertDBTraces("h2")

    cleanup:
    connection?.close()
  }

  def "h2 prepare on wrapped conn, execute wrapped stmt"() {
    setup:
    def connection = runUnderTrace("initialization") {
      new Driver().connect("jdbc:h2:mem:", null)
    }
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.clear()

    when:
    runUnderTrace("parent") {
      def statement = new WrappedConnection(connection).prepareStatement(query)
      def wrappedStatement = new WrappedPreparedStatement(statement)
      wrappedStatement.execute()
    }

    then:
    assertDBTraces("h2")

    cleanup:
    connection?.close()
  }

  // All of the tests should return the same result
  def assertDBTraces(String database = "testdb", String operation = "SELECT") {
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        span {
          operationName "${database}.query"
          serviceName "${database}"
          resourceName obfuscatedQuery
          spanType DDSpanTypes.SQL
          childOfPrevious()
          errored false
          tags {
            if (database == "testdb") {
              "$Tags.PEER_HOSTNAME" "localhost"
            }
            "$Tags.COMPONENT" "java-jdbc-prepared_statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" "${database}"
            "$Tags.DB_OPERATION" "${operation}"
            defaultTagsNoPeerService()
          }
        }
      }
    }

    return true
  }
}

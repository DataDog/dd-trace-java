import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDSpanTypes
import datadog.trace.instrumentation.api.Tags

class SlickTest extends AgentTestRunner {

  // Can't be @Shared, otherwise the work queue is initialized before the instrumentation is applied
  def database = new SlickUtils()

  def "Basic statement generates spans"() {
    setup:
    def future = database.startQuery(SlickUtils.TestQuery())
    def result = database.getResults(future)

    expect:
    result == SlickUtils.TestValue()

    assertTraces(1) {
      trace(0, 2) {
        span(0) {
          serviceName "unnamed-java-app"
          operationName "trace.annotation"
          resourceName "SlickUtils.startQuery"
          parent()
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
        span(1) {
          operationName "${SlickUtils.Driver()}.query"
          serviceName SlickUtils.Driver()
          resourceName SlickUtils.TestQuery()
          spanType DDSpanTypes.SQL
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "java-jdbc-prepared_statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT

            "$Tags.DB_TYPE" SlickUtils.Driver()
            "$Tags.DB_USER" SlickUtils.Username()

            "db.instance" SlickUtils.Db()
            "span.origin.type" "org.h2.jdbc.JdbcPreparedStatement"

            defaultTags()
          }
        }
      }
    }
  }

  def "Concurrent requests do not throw exception"() {
    setup:
    def sleepFuture = database.startQuery(SlickUtils.SleepQuery())

    def future = database.startQuery(SlickUtils.TestQuery())
    def result = database.getResults(future)

    database.getResults(sleepFuture)

    expect:
    result == SlickUtils.TestValue()

    // Expect two traces because two queries have been run
    assertTraces(2) {
      trace(0, 2, {
        span(0) {}
        span(1) { spanType DDSpanTypes.SQL }
      })
      trace(1, 2, {
        span(0) {}
        span(1) { spanType DDSpanTypes.SQL }
      })
    }
  }
}

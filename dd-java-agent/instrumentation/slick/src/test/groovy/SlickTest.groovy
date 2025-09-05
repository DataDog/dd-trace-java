import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags

class SlickTest extends InstrumentationSpecification {

  // Can't be @Shared, otherwise the work queue is initialized before the instrumentation is applied
  def database = new SlickUtils(TEST_WRITER)

  def "Basic statement generates spans"() {
    setup:
    def future = database.startQuery(SlickUtils.TestQuery())
    def result = database.getResults(future)

    expect:
    result == SlickUtils.TestValue()

    assertTraces(1) {
      trace(2) {
        span {
          operationName "trace.annotation"
          resourceName "SlickUtils.startQuery"
          parent()
          errored false
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
        span {
          operationName "${SlickUtils.Driver()}.query"
          serviceName SlickUtils.Driver()
          resourceName SlickUtils.ObfuscatedTestQuery()
          spanType DDSpanTypes.SQL
          childOf span(0)
          errored false
          tags {
            "$Tags.COMPONENT" "java-jdbc-prepared_statement"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.DB_TYPE" SlickUtils.Driver()
            "$Tags.DB_INSTANCE" SlickUtils.Db()
            "$Tags.DB_USER" SlickUtils.Username()
            "$Tags.DB_OPERATION" "SELECT"
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
      trace(2) {
        span {
          operationName "trace.annotation"
          resourceName "SlickUtils.startQuery"
        }
        span { spanType DDSpanTypes.SQL }
      }
      trace(2) {
        span {
          operationName "trace.annotation"
          resourceName "SlickUtils.startQuery"
        }
        span { spanType DDSpanTypes.SQL }
      }
    }
  }
}

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.CorrelationIdentifier

class TraceCorrelationTest extends AgentTestRunner {

  def "access trace correlation only under trace"() {
    when:
    def span = TEST_TRACER.startSpan("myspan")
    def scope = TEST_TRACER.activateSpan(span)

    then:
    CorrelationIdentifier.traceId == span.traceId.toString()
    CorrelationIdentifier.spanId == span.spanId.toString()

    when:
    scope.close()
    span.finish()

    then:
    CorrelationIdentifier.traceId == "0"
    CorrelationIdentifier.spanId == "0"
  }
}

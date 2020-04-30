import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.CorrelationIdentifier

class TraceCorrelationTest extends AgentTestRunner {

  def "access trace correlation only under trace"() {
    when:
    def span = getTestTracer().startSpan("myspan")
    def scope = getTestTracer().activateSpan(span, true)

    then:
    CorrelationIdentifier.traceId == span.traceId.toString()
    CorrelationIdentifier.spanId == span.spanId.toString()

    when:
    scope.close()

    then:
    CorrelationIdentifier.traceId == "0"
    CorrelationIdentifier.spanId == "0"
  }
}

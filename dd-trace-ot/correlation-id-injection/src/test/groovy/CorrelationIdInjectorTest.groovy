import datadog.opentracing.DDTracer
import datadog.trace.api.CorrelationIdentifier
import datadog.trace.api.GlobalTracer
import datadog.trace.test.util.DDSpecification

abstract class CorrelationIdInjectorTest extends DDSpecification {
  def logPattern = "TRACE_ID=%X{${CorrelationIdentifier.getTraceIdKey()}} SPAN_ID=%X{${CorrelationIdentifier.getSpanIdKey()}} %m"

  def "test correlation id injection"() {
    setup:
    def tracer = buildTracer()
    def journal = buildJournal()
    def logger = buildLogger()

    when:
    logger.log("Event without context")

    then:
    journal.nextLog() == "TRACE_ID= SPAN_ID= Event without context"

    when:
    def rootSpan = tracer.buildSpan("operation1").start()
    def rootScope = tracer.activateSpan(rootSpan)
    logger.log("Event with root span context")

    then:
    journal.nextLog() == "TRACE_ID=${CorrelationIdentifier.traceId} SPAN_ID=${CorrelationIdentifier.spanId} Event with root span context"

    when:
    def childSpan = tracer.buildSpan("operation1").asChildOf(rootSpan).start()
    def childScope = tracer.activateSpan(childSpan)
    logger.log("Event with child span context")

    then:
    journal.nextLog() == "TRACE_ID=${CorrelationIdentifier.traceId} SPAN_ID=${CorrelationIdentifier.spanId} Event with child span context"

    when:
    childScope.close()
    childSpan.finish()
    logger.log("Event with root span context")

    then:
    journal.nextLog() == "TRACE_ID=${CorrelationIdentifier.traceId} SPAN_ID=${CorrelationIdentifier.spanId} Event with root span context"

    when:
    rootScope.close()
    rootSpan.finish()
    logger.log("Event without context")

    then:
    journal.nextLog() == "TRACE_ID= SPAN_ID= Event without context"

    cleanup:
    tracer.close()
  }

  def buildTracer() {
    DDTracer tracer = new DDTracer.DDTracerBuilder().build()
    GlobalTracer.registerIfAbsent(tracer)
    return tracer
  }

  abstract LogJournal buildJournal()

  abstract TestLogger buildLogger()

  interface LogJournal {
    String nextLog()
  }

  interface TestLogger {
    void log(String message)
  }
}

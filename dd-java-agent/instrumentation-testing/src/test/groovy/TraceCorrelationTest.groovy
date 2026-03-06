import datadog.trace.agent.test.InstrumentationSpecification

import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_128_BIT_TRACEID_LOGGING_ENABLED
import static datadog.trace.api.config.TracerConfig.TRACE_128_BIT_TRACEID_GENERATION_ENABLED

import datadog.trace.api.GlobalTracer

class TraceCorrelationTest extends InstrumentationSpecification {

  def "access trace correlation only under trace"() {
    when:
    def span = TEST_TRACER.startSpan("test", "myspan")
    def scope = TEST_TRACER.activateManualSpan(span)

    then:
    GlobalTracer.get().traceId == (span.traceId.toHighOrderLong() == 0 ? span.traceId.toString() : span.traceId.toHexString())
    GlobalTracer.get().spanId == span.spanId.toString()

    when:
    scope.close()
    span.finish()

    then:
    GlobalTracer.get().traceId == "0"
    GlobalTracer.get().spanId == "0"
  }
}
class Trace128bitCorrelationTest extends TraceCorrelationTest {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TRACE_128_BIT_TRACEID_GENERATION_ENABLED, "true")
    injectSysConfig(TRACE_128_BIT_TRACEID_LOGGING_ENABLED, "true")
  }
}

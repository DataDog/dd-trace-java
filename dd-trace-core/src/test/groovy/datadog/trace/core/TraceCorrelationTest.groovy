package datadog.trace.core

import datadog.trace.common.writer.ListWriter
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_128_BIT_TRACEID_LOGGING_ENABLED
import static datadog.trace.api.config.TracerConfig.TRACE_128_BIT_TRACEID_GENERATION_ENABLED

class TraceCorrelationTest extends DDCoreSpecification {
  def "get trace id without trace (128b: #log128bTraceId)"() {
    setup:
    injectSysConfig(TRACE_128_BIT_TRACEID_GENERATION_ENABLED, log128bTraceIdConfigValue)
    injectSysConfig(TRACE_128_BIT_TRACEID_LOGGING_ENABLED, log128bTraceIdConfigValue)

    when:
    def tracer = tracerBuilder().writer(new ListWriter()).build()
    def span = tracer.buildSpan("test").start()
    def scope = tracer.activateSpan(span)
    scope.close()

    then:
    "0" == tracer.getTraceId()

    cleanup:
    scope.close()
    span.finish()
    tracer.close()

    where:
    log128bTraceId << [true, false]
    log128bTraceIdConfigValue = log128bTraceId.toString()
  }

  def "get trace id with trace (128b: #log128bTraceId)"() {
    setup:
    injectSysConfig(TRACE_128_BIT_TRACEID_GENERATION_ENABLED, log128bTraceIdConfigValue)
    injectSysConfig(TRACE_128_BIT_TRACEID_LOGGING_ENABLED, log128bTraceIdConfigValue)

    when:
    def tracer = tracerBuilder().writer(new ListWriter()).build()
    def span = tracer.buildSpan("test").start()
    def scope = tracer.activateSpan(span)

    then:
    def traceId = ((DDSpan) scope.span()).traceId
    def formattedTraceId = log128bTraceId ? traceId.toHexString() : traceId.toString()
    formattedTraceId == tracer.getTraceId()

    cleanup:
    scope.close()
    span.finish()
    tracer.close()

    where:
    log128bTraceId << [true, false]
    log128bTraceIdConfigValue = log128bTraceId.toString()
  }

  def "get span id without span"() {
    when:
    def tracer = tracerBuilder().writer(new ListWriter()).build()
    def span = tracer.buildSpan("test").start()
    def scope = tracer.activateSpan(span)
    scope.close()

    then:
    "0" == tracer.getSpanId()

    cleanup:
    scope.close()
    span.finish()
    tracer.close()
  }

  def "get span id with trace"() {
    when:
    def tracer = tracerBuilder().writer(new ListWriter()).build()
    def span = tracer.buildSpan("test").start()
    def scope = tracer.activateSpan(span)

    then:
    ((DDSpan) scope.span()).spanId.toString() == tracer.getSpanId()

    cleanup:
    scope.close()
    span.finish()
    tracer.close()
  }
}

package datadog.trace.core.traceinterceptor

import datadog.trace.api.DDTags
import datadog.trace.common.writer.ListWriter

import datadog.trace.core.test.DDCoreSpecification

import spock.lang.Timeout

@Timeout(10)
class LatencyTraceInterceptorTest  extends DDCoreSpecification {


  def "test set sampling priority according to latency"() {
    setup:

    injectSysConfig("trace.partial.flush.enabled", partialFlushEnabled)
    injectSysConfig("trace.latency.interceptor.value", latencyThreshold)

    when:
    def writer = new ListWriter()
    def tracer = tracerBuilder().writer(writer).build()

    def spanSetup = tracer.buildSpan("test","my_operation_name").withTag(priorityTag, true).start()
    sleep(minDuration)
    spanSetup.finish()

    then:
    def trace = writer.firstTrace()
    trace.size() == 1
    def span = trace[0]
    span.context().getSamplingPriority() == expected

    cleanup:
    tracer.close()

    where:
    partialFlushEnabled | latencyThreshold | priorityTag                   | minDuration    | expected
    "true"              |       "200"      | DDTags.MANUAL_KEEP            |   10           |   2
    "true"              |       "200"      | DDTags.MANUAL_DROP            |   10           |  -1
    "true"              |       "200"      | DDTags.MANUAL_KEEP            |   300          |   2
    "true"              |       "200"      | DDTags.MANUAL_DROP            |   300          |  -1
    "false"             |       "200"      | DDTags.MANUAL_KEEP            |   10           |   2
    "false"             |       "200"      | DDTags.MANUAL_DROP            |   10           |  -1
    "false"             |       "200"      | DDTags.MANUAL_KEEP            |   300          |   2
    "false"             |       "200"      | DDTags.MANUAL_DROP            |   300          |   2
  }


}

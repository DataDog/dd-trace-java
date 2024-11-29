package datadog.trace.core.traceinterceptor

import datadog.trace.api.DDTags
import datadog.trace.common.writer.ListWriter

import datadog.trace.core.test.DDCoreSpecification

import spock.lang.Timeout

@Timeout(10)
class LatencyTraceInterceptorTest  extends DDCoreSpecification {


  def "test set sampling priority according to latency"() {
    setup:
    def writer = new ListWriter()
    def props = new Properties()
    props.setProperty("trace.partial.flush.enabled", partialFlushEnabled)
    props.setProperty("trace.latency.interceptor.value", latencyThreshold)

    def tracer = tracerBuilder().withProperties(props).writer(writer).build()

    def spanSetup = tracer.buildSpan("test","my_operation_name").withTag(tagname, true).start()
    sleep(duration)
    spanSetup.finish()

    expect:
    def trace = writer.firstTrace()
    trace.size() == 1
    def span = trace[0]

    span.context().getSamplingPriority() == expected

    tracer.close()
    where:
    partialFlushEnabled | latencyThreshold | tagname                      | duration    | expected
    "true"              |       "102"     | DDTags.MANUAL_KEEP            |   100       |  2
    "true"              |       "102"    | DDTags.MANUAL_DROP             |   100       |  -1
    "true"              |       "102"     | DDTags.MANUAL_KEEP            |   105       |  2
    "true"              |       "102"     | DDTags.MANUAL_DROP            |   105       |  -1
    // "false"             |       "102"     | DDTags.MANUAL_KEEP            |   100       |  2
    // "false"             |       "102"     | DDTags.MANUAL_DROP            |   100       |  -1
    // "false"             |       "102"     | DDTags.MANUAL_KEEP            |   105       |  2
    // "false"             |       "102"     | DDTags.MANUAL_DROP            |   105       |  2
  }
}

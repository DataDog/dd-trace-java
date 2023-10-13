package datadog.trace.civisibility.interceptor

import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.test.DDCoreSpecification
import spock.lang.Timeout

@Timeout(10)
class CiVisibilityTraceInterceptorTest extends DDCoreSpecification {

  def writer = new ListWriter()
  def tracer = tracerBuilder().writer(writer).build()

  def cleanup() {
    tracer?.close()
  }

  def "discard a trace that does not come from a test"() {
    tracer.addTraceInterceptor(CiVisibilityTraceInterceptor.INSTANCE)
    tracer.buildSpan("sample-span").start().finish()

    expect:
    writer.size() == 0
  }

  def "add ciapp origin and tracer version to spans of type #spanType"() {
    setup:
    tracer.addTraceInterceptor(CiVisibilityTraceInterceptor.INSTANCE)

    tracer.buildSpan("sample-span").withSpanType(spanType).start().finish()
    writer.waitForTraces(1)

    expect:
    def trace = writer.firstTrace()
    trace.size() == 1

    def span = trace[0]

    span.context().origin == CiVisibilityTraceInterceptor.CIAPP_TEST_ORIGIN
    span.getTag(DDTags.LIBRARY_VERSION_TAG_KEY) != null

    where:
    spanType << [DDSpanTypes.TEST, DDSpanTypes.TEST_SUITE_END, DDSpanTypes.TEST_MODULE_END]
  }
}

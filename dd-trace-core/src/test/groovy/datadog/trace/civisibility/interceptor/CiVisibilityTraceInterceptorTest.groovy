package datadog.trace.civisibility.interceptor

import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.civisibility.CIConstants
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.DDSpanContext
import datadog.trace.core.test.DDCoreSpecification
import spock.lang.Timeout

@Timeout(10)
class CiVisibilityTraceInterceptorTest extends DDCoreSpecification {

  def writer = new ListWriter()
  def tracer = tracerBuilder().writer(writer).build()

  def cleanup() {
    tracer?.close()
  }

  def "discard a trace that does not come from ci app"() {
    tracer.addTraceInterceptor(CiVisibilityTraceInterceptor.INSTANCE)
    tracer.buildSpan("sample-span").start().finish()

    expect:
    writer.size() == 0
  }

  def "do not discard a trace that comes from ci app"() {
    tracer.addTraceInterceptor(CiVisibilityTraceInterceptor.INSTANCE)

    def span = tracer.buildSpan("sample-span").start()
    ((DDSpanContext) span.context()).origin = CIConstants.CIAPP_TEST_ORIGIN
    span.finish()

    expect:
    writer.size() == 1
  }

  def "add tracer version to spans of type #spanType"() {
    setup:
    tracer.addTraceInterceptor(CiVisibilityTraceInterceptor.INSTANCE)


    def span = tracer.buildSpan("sample-span").withSpanType(spanType).start()
    ((DDSpanContext) span.context()).origin = CIConstants.CIAPP_TEST_ORIGIN
    span.finish()
    writer.waitForTraces(1)

    expect:
    def trace = writer.firstTrace()
    trace.size() == 1

    def receivedSpan = trace[0]

    receivedSpan.getTag(DDTags.LIBRARY_VERSION_TAG_KEY) != null

    where:
    spanType << [
      DDSpanTypes.TEST,
      DDSpanTypes.TEST_SUITE_END,
      DDSpanTypes.TEST_MODULE_END,
      DDSpanTypes.TEST_SESSION_END
    ]
  }
}

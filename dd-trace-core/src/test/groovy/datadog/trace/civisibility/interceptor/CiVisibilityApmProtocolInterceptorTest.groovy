package datadog.trace.civisibility.interceptor

import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.test.DDCoreSpecification
import spock.lang.Timeout

@Timeout(10)
class CiVisibilityApmProtocolInterceptorTest extends DDCoreSpecification {

  def writer = new ListWriter()
  def tracer = tracerBuilder().writer(writer).build()

  def cleanup() {
    tracer?.close()
  }

  def "test suite and test module spans are filtered out"() {
    setup:
    tracer.addTraceInterceptor(CiVisibilityApmProtocolInterceptor.INSTANCE)

    tracer.buildSpan("test-module").withSpanType(DDSpanTypes.TEST_MODULE_END).start().finish()
    tracer.buildSpan("test-suite").withSpanType(DDSpanTypes.TEST_SUITE_END).start().finish()
    tracer.buildSpan("test").withSpanType(DDSpanTypes.TEST).start().finish()

    writer.waitForTraces(1)

    expect:
    def trace = writer.firstTrace()
    trace.size() == 1

    def span = trace[0]
    span.operationName == "test"
  }

  def "test session, test module and test suite IDs are nullified"() {
    setup:
    tracer.addTraceInterceptor(CiVisibilityApmProtocolInterceptor.INSTANCE)

    def testSpan = tracer.buildSpan("test").withSpanType(DDSpanTypes.TEST).start()
    testSpan.setTag(Tags.TEST_SESSION_ID, "session ID")
    testSpan.setTag(Tags.TEST_MODULE_ID, "module ID")
    testSpan.setTag(Tags.TEST_SUITE_ID, "suite ID")
    testSpan.setTag("random tag", "random value")
    testSpan.finish()

    writer.waitForTraces(1)

    expect:
    def trace = writer.firstTrace()
    trace.size() == 1

    def span = trace[0]

    span.getTag(Tags.TEST_SESSION_ID) == null
    span.getTag(Tags.TEST_MODULE_ID) == null
    span.getTag(Tags.TEST_SUITE_ID) == null
    span.getTag("random tag") == "random value"
  }
}

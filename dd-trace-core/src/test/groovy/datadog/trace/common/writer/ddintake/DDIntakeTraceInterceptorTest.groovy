package datadog.trace.common.writer.ddintake

import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.test.DDCoreSpecification
import spock.lang.Timeout

@Timeout(100)
class DDIntakeTraceInterceptorTest extends DDCoreSpecification {

  def writer = new ListWriter()
  def tracer = tracerBuilder().writer(writer).build()

  def setup(){
    tracer.addTraceInterceptor(DDIntakeTraceInterceptor.INSTANCE)
  }

  def cleanup() {
    tracer?.close()
  }

  def "test normalization for dd intake"() {
    setup:
    tracer.buildSpan("my-operation-name")
      .withResourceName("my-resource-name")
      .withSpanType("my-span-type")
      .withServiceName("my-service-name")
      .withTag("some-tag-key", "some-tag-value")
      .withTag("env","     My_____Env     ")
      .withTag(Tags.HTTP_STATUS, httpStatus)
      .start().finish()
    writer.waitForTraces(1)

    expect:
    def trace = writer.firstTrace()
    trace.size() == 1

    def span = trace[0]

    span.getServiceName() == "my-service-name"
    span.getOperationName() == "my_operation_name"
    span.getResourceName() == "my-resource-name"
    span.getSpanType() == "my-span-type"
    span.getTag("some-tag-key") == "some-tag-value"
    span.getTag("env") == "my_env"
    span.getTag(Tags.HTTP_STATUS) == expectedHttpStatus

    where:
    httpStatus | expectedHttpStatus
    null | null
    "" | null
    "500" | 500
    500 | 500
    600 | null
  }
}

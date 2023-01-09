package datadog.opentelemetry

import datadog.opentelemetry.trace.DDTracer
import datadog.trace.api.DDTags
import datadog.trace.common.writer.ListWriter
import datadog.trace.test.util.DDSpecification
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Scope

import static datadog.trace.agent.test.asserts.ListWriterAssert.assertTraces

class OpenTelemetryTest extends DDSpecification {

  def "test tracer creation"() {

    setup:
    def openTelemetry = GlobalOpenTelemetry.get()
    def tracer = openTelemetry.getTracer("instrumentation-name")

    expect:
    tracer != null
    tracer instanceof DDTracer
  }

  def "test simple span"() {
    setup:
    def writer = new ListWriter()
    def tracer = new DDTracer("someService", writer)

    when:
    Scope scope
    Span s = tracer.spanBuilder("someOperation").startSpan()
    try {
      scope = s.makeCurrent()
      s.setAttribute(DDTags.SERVICE_NAME, "someService")
    } finally {
      scope.close()
      s.end()
    }
    writer.waitForTraces(1)

    then:
    assertTraces(writer, 1) {
      trace(1) {
        span {
          serviceName "someService"
          operationName "someOperation"
          resourceName "someOperation"
          tags {
            defaultTags()
          }
        }
      }
    }
  }
}

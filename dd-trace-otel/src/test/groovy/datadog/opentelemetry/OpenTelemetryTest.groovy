package datadog.opentelemetry

import datadog.opentelemetry.trace.DDTracer
import datadog.trace.test.util.DDSpecification
import io.opentelemetry.api.GlobalOpenTelemetry

class OpenTelemetryTest extends DDSpecification {

  def "test tracer creation"() {

    setup:
    def openTelemetry = GlobalOpenTelemetry.get()
    def tracer = openTelemetry.getTracer("unit-tests")

    expect:
    tracer != null
    tracer instanceof DDTracer
  }
}

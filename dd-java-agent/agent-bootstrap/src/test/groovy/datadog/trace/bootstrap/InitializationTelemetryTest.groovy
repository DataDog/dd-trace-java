package datadog.trace.bootstrap

import datadog.trace.test.util.DDSpecification

class InitializationTelemetryTest extends DDSpecification {
  def "telemetry wrapper - null case"() {
    expect:
    InitializationTelemetry.proxy(null) == InitializationTelemetry.noOpInstance()
  }

  def "telemetry wrapper - wrap bootstrap"() {
    // TODO: Figure out how to test the wrapper fully
    expect:
    InitializationTelemetry.proxy(new Object()) != InitializationTelemetry.noOpInstance()
  }
}
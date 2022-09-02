package datadog.communication.ddagent

import datadog.trace.test.util.DDSpecification

class TracerVersionTest extends DDSpecification {
  def "test tracer version"() {
    expect:
    TracerVersion.TRACER_VERSION == "TEST-SNAPSHOT~test"
  }
}

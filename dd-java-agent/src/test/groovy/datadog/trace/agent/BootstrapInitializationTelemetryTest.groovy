package datadog.trace.agent

import datadog.trace.bootstrap.BootstrapInitializationTelemetry
import datadog.trace.bootstrap.JsonBuffer
import spock.lang.Specification

class BootstrapInitializationTelemetryTest extends Specification {
  def "metainfo"() {
    setup:
    def (initTelemetry, capture) = createTelemetry()

    when:
    initTelemetry.finish()
    
    then:
    capture.json() == ""
  }

  static createTelemetry() {
    var capture = new Capture()
    return [
      new BootstrapInitializationTelemetry.JsonBased(capture),
      capture]
  }

  static class Capture implements BootstrapInitializationTelemetry.JsonSender {
    JsonBuffer buffer

    void send(JsonBuffer buffer) {
      this.buffer = buffer
    }

    String json() {
      return this.buffer.toString()
    }
  }
}

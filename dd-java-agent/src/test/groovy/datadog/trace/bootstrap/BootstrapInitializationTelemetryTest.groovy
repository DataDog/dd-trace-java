package datadog.trace.agent

import datadog.trace.bootstrap.BootstrapInitializationTelemetry
import datadog.trace.bootstrap.JsonBuffer
import spock.lang.Specification
import groovy.util.Proxy

class BootstrapInitializationTelemetryTest extends Specification {
  def initTelemetry, capture

  def setup() {
    def capture = new Capture()
    def initTelemetry = new BootstrapInitializationTelemetry.JsonBased(capture)

    // There's an annoying interaction between our bootstrap injection
    // and the GroovyClassLoader class resolution.  Groovy resolves the import
    // against the application ClassLoader, but when a method invocation
    // happens it resolves the invocation against the bootstrap classloader.

    // To side step this problem, put a Groovy Proxy around the object under test
    def initTelemetryProxy = new Proxy()
    initTelemetryProxy.setAdaptee(initTelemetry)

    this.initTelemetry = initTelemetryProxy
    this.capture = capture
  }

  def "real example"() {
    when:
    initTelemetry.initMetaInfo("runtime_name", "java")
    initTelemetry.initMetaInfo("runtime_version", "1.8.0_382")

    initTelemetry.onError(new Exception("foo"))
    initTelemetry.finish()

    then:
    capture.json() == '{"metadata":{"runtime_name":"java","runtime_version":"1.8.0_382"},"points":[{"name":"library_entrypoint.error","tags":["error_type:java.lang.Exception"]},{"name":"library_entrypoint.complete"}]}'
  }

  def "trivial completion check"() {
    when:
    initTelemetry.finish()

    then:
    capture.json().contains("library_entrypoint.complete")
  }

  def "trivial incomplete check"() {
    when:
    initTelemetry.markIncomplete()
    initTelemetry.finish()

    then:
    !capture.json().contains("library_entrypoint.complete")
  }

  def "incomplete on fatal error"() {
    when:
    initTelemetry.onFatalError(new Exception("foo"))
    initTelemetry.finish()

    then:
    !capture.json().contains("library_entrypoint.complete")
  }

  def "incomplete on abort"() {
    when:
    initTelemetry.onAbort("reason")
    initTelemetry.finish()

    then:
    !capture.json().contains("library_entrypoint.complete")
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

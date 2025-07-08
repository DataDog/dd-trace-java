package datadog.trace.bootstrap

import spock.lang.Specification

import static java.nio.charset.StandardCharsets.UTF_8

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

    // codeNarc was incorrectly flagging "import groovy.util.Proxy" as unnecessary,
    // since groovy.util is imported implicitly.  However, java.util is also
    // implicitly imported and also contains a Proxy class, so need to use the
    // full name inline to disambiguate and pass codeNarc.
    def initTelemetryProxy = new groovy.util.Proxy()
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
    capture.json() == '{"metadata":{"runtime_name":"java","runtime_version":"1.8.0_382","result":"error","result_reason":"foo","result_class":"internal_error"},"points":[{"name":"library_entrypoint.error","tags":["error_type:java.lang.Exception"]},{"name":"library_entrypoint.complete"}]}'
  }

  def "test fatal error"() {
    when:
    initTelemetry.initMetaInfo("runtime_name", "java")
    initTelemetry.initMetaInfo("runtime_version", "1.8.0_382")

    initTelemetry.onFatalError(new Exception("foo"))
    initTelemetry.finish()

    then:
    capture.json() == '{"metadata":{"runtime_name":"java","runtime_version":"1.8.0_382","result":"error","result_reason":"foo","result_class":"internal_error"},"points":[{"name":"library_entrypoint.error","tags":["error_type:java.lang.Exception"]}]}'
  }

  def "test abort"() {
    when:
    initTelemetry.initMetaInfo("runtime_name", "java")
    initTelemetry.initMetaInfo("runtime_version", "1.8.0_382")

    initTelemetry.onAbort("jdk_tool")
    initTelemetry.finish()

    then:
    capture.json() == '{"metadata":{"runtime_name":"java","runtime_version":"1.8.0_382","result":"abort","result_reason":"jdk_tool","result_class":"unsupported_binary"},"points":[{"name":"library_entrypoint.abort","tags":["reason:jdk_tool"]}]}'
  }

  def "test success"() {
    when:
    initTelemetry.initMetaInfo("runtime_name", "java")
    initTelemetry.initMetaInfo("runtime_version", "1.8.0_382")

    initTelemetry.finish()

    then:
    capture.json() == '{"metadata":{"runtime_name":"java","runtime_version":"1.8.0_382","result":"success","result_reason":"Successfully configured ddtrace package","result_class":"success"},"points":[{"name":"library_entrypoint.complete"}]}'
  }

  def "test abort other-java-agents"() {
    when:
    initTelemetry.initMetaInfo("runtime_name", "java")
    initTelemetry.initMetaInfo("runtime_version", "1.8.0_382")

    initTelemetry.onAbort("other-java-agents")
    initTelemetry.finish()

    then:
    capture.json() == '{"metadata":{"runtime_name":"java","runtime_version":"1.8.0_382","result":"abort","result_reason":"other-java-agents","result_class":"already_instrumented"},"points":[{"name":"library_entrypoint.abort","tags":["reason:other-java-agents"]}]}'
  }

  def "test abort unknown"() {
    when:
    initTelemetry.initMetaInfo("runtime_name", "java")
    initTelemetry.initMetaInfo("runtime_version", "1.8.0_382")

    initTelemetry.onAbort("foo")
    initTelemetry.finish()

    then:
    capture.json() == '{"metadata":{"runtime_name":"java","runtime_version":"1.8.0_382","result":"abort","result_reason":"foo","result_class":"unknown"},"points":[{"name":"library_entrypoint.abort","tags":["reason:foo"]}]}'
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
    String json

    void send(byte[] payload) {
      this.json = new String(payload, UTF_8)
    }

    String json() {
      return this.json
    }
  }
}

package datadog.trace.bootstrap

import groovy.json.JsonBuilder
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
    this.initTelemetry.initMetaInfo("runtime_name", "java")
    this.initTelemetry.initMetaInfo("runtime_version", "1.8.0_382")
    this.capture = capture
  }

  def "test success"() {
    when:
    initTelemetry.finish()

    then:
    capture.json() == json("success", "success", "Successfully configured ddtrace package",
      [[name: "library_entrypoint.complete"]])
  }

  def "real example"() {
    when:
    initTelemetry.onError(new Exception("foo"))
    initTelemetry.finish()

    then:
    capture.json() == json("error", "internal_error", "foo", [
      [name: "library_entrypoint.error", tags: ["error_type:java.lang.Exception"]],
      [name: "library_entrypoint.complete"]
    ])
  }

  def "test abort"() {
    when:
    initTelemetry.onAbort(reasonCode)
    initTelemetry.finish()

    then:
    capture.json() == json("abort", resultClass, reasonCode,
      [[name: "library_entrypoint.abort", tags: ["reason:${reasonCode}"]]])

    where:
    reasonCode            | resultClass
    "jdk_tool"            | "unsupported_binary"
    "already_initialized" | "already_instrumented"
    "other-java-agents"   | "incompatible_library"
    "foo"                 | "unknown"
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
    capture.json() == json("error", "internal_error", "foo",
      [[name: "library_entrypoint.error", tags: ["error_type:java.lang.Exception"]]])
  }

  def "incomplete on abort"() {
    when:
    initTelemetry.onAbort("reason")
    initTelemetry.finish()

    then:
    !capture.json().contains("library_entrypoint.complete")
  }

  def "unwind root cause"() {
    when:
    initTelemetry.onError(new Exception("top cause", new FileNotFoundException("root cause")))
    initTelemetry.finish()

    then:
    capture.json() == json("error", "internal_error", "top cause", [
      [name: "library_entrypoint.error", tags: ["error_type:java.io.FileNotFoundException", "error_type:java.lang.Exception"]],
      [name: "library_entrypoint.complete"]
    ])
  }

  private static String json(String result, String resultClass, String resultReason, List points) {
    return """{"metadata":{"runtime_name":"java","runtime_version":"1.8.0_382","result":"${result}","result_class":"${resultClass}","result_reason":"${resultReason}"},"points":${new JsonBuilder(points)}}"""
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

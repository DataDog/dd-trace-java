package datadog.trace.bootstrap

import groovy.json.JsonBuilder
import spock.lang.Specification

class BootstrapInitializationTelemetryTest extends Specification {
  Capture capture
  def initTelemetry

  def setup() {
    capture = new Capture()

    // There's an annoying interaction between our bootstrap injection
    // and the GroovyClassLoader class resolution.  Groovy resolves the import
    // against the application ClassLoader, but when a method invocation
    // happens it resolves the invocation against the bootstrap classloader.
    //
    // To side step this problem, put a Groovy Proxy around the object under test
    // codeNarc was incorrectly flagging "import groovy.util.Proxy" as unnecessary,
    // since groovy.util is imported implicitly.  However, java.util is also
    // implicitly imported and also contains a Proxy class, so need to use the
    // full name inline to disambiguate and pass codeNarc.
    def initTelemetryProxy = new groovy.util.Proxy()
    initTelemetryProxy.setAdaptee(new BootstrapInitializationTelemetry.JsonBased(capture))
    this.initTelemetry = initTelemetryProxy
  }

  def "test happy path"() {
    when:
    initTelemetry.finish()

    then:
    assertJson("success", "success", "Successfully configured ddtrace package", [[name: "library_entrypoint.complete"]])
  }

  def "test non fatal error as text"() {
    when:
    initTelemetry.onError("some reason")
    initTelemetry.finish()

    then:
    assertJson("error", "unknown", "some reason", [
      [name: "library_entrypoint.error", tags: ["error_type:some reason"]],
      [name: "library_entrypoint.complete"]
    ])
  }

  def "test non fatal error as exception"() {
    when:
    initTelemetry.onError(new Exception("non fatal error"))
    initTelemetry.finish()

    then:
    assertJson("error", "internal_error", "non fatal error", [
      [name: "library_entrypoint.error", tags: ["error_type:java.lang.Exception"]],
      [name: "library_entrypoint.complete"]
    ])
  }

  def "test abort"() {
    when:
    initTelemetry.onAbort(reasonCode)
    initTelemetry.finish()

    then:
    assertJson("abort", resultClass, reasonCode, [[name: "library_entrypoint.abort", tags: ["reason:${reasonCode}"]]])

    where:
    reasonCode            | resultClass
    "jdk_tool"            | "unsupported_binary"
    "already_initialized" | "already_instrumented"
    "other-java-agents"   | "incompatible_library"
    "foo"                 | "unknown"
  }

  def "test fatal error"() {
    when:
    initTelemetry.onFatalError(new Exception("fatal error"))
    initTelemetry.finish()

    then:
    assertJson("error", "internal_error", "fatal error", [[name: "library_entrypoint.error", tags: ["error_type:java.lang.Exception"]]])
  }

  def "test unwind root cause"() {
    when:
    initTelemetry.onError(new Exception("top cause", new FileNotFoundException("root cause")))
    initTelemetry.finish()

    then:
    assertJson("error", "internal_error", "top cause", [
      [name: "library_entrypoint.error", tags: ["error_type:java.io.FileNotFoundException", "error_type:java.lang.Exception"]],
      [name: "library_entrypoint.complete"]
    ])
  }

  private boolean assertJson(String result, String resultClass, String resultReason, List points) {
    def expectedJson = """{"metadata":{"result":"${result}","result_class":"${resultClass}","result_reason":"${resultReason}"},"points":${new JsonBuilder(points)}}"""
    def actualJson = capture.json()

    assert expectedJson == actualJson

    return true
  }

  static class Capture implements BootstrapInitializationTelemetry.JsonSender {
    Object telemetry

    void send(Object telemetry) {
      this.telemetry = telemetry
    }

    String json() {
      return telemetry.toString()
    }
  }
}

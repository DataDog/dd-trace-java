package datadog.trace.bootstrap

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
    assertMeta("success", "success", "Successfully configured ddtrace package")
    assertPoints(true, "library_entrypoint.complete", [])
  }

  def "test non fatal error as text"() {
    when:
    initTelemetry.onError("some reason")
    initTelemetry.finish()

    then:
    assertMeta("error", "unknown", "some reason")
    assertPoints(true, "library_entrypoint.error", ["error_type:some reason"])
  }

  def "test non fatal error as exception"() {
    when:
    initTelemetry.onError(new Exception("non fatal error"))
    initTelemetry.finish()

    then:
    assertMeta("error", "internal_error", "non fatal error")
    assertPoints(true, "library_entrypoint.error", ["error_type:java.lang.Exception"])
  }

  def "test abort"() {
    when:
    initTelemetry.onAbort(reasonCode)
    initTelemetry.finish()

    then:
    assertMeta("abort", resultClass, reasonCode)
    assertPoints(false, "library_entrypoint.abort", ["reason:${reasonCode}"])

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
    assertMeta("error", "internal_error", "fatal error")
    assertPoints(false, "library_entrypoint.error", ["error_type:java.lang.Exception"])
  }

  def "test unwind root cause"() {
    when:
    initTelemetry.onError(new Exception("top cause", new FileNotFoundException("root cause")))
    initTelemetry.finish()

    then:
    assertMeta("error", "internal_error", "top cause")
    assertPoints(true, "library_entrypoint.error", ["error_type:java.io.FileNotFoundException", "error_type:java.lang.Exception"])
  }

  def assertMeta(String result, String resultClass, String resultReason) {
    def meta = capture.meta

    assert meta.get("result") == result
    assert meta.get("result_class") == resultClass
    assert meta.get("result_reason") == resultReason

    return true
  }

  def assertPoints(boolean complete, String point, List<String> tags) {
    def points = capture.points

    assert points.containsKey("library_entrypoint.complete") == complete
    assert points.get(point) == tags

    return true
  }

  static class Capture implements BootstrapInitializationTelemetry.JsonSender {
    Map<String, String> meta
    Map<String, List<String>> points

    void send(Map<String, String> meta, Map<String, List<String>> points) {
      this.meta = meta
      this.points = points
    }
  }
}

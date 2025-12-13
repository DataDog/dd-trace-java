package datadog.trace.bootstrap.instrumentation.appsec

import datadog.trace.api.gateway.RequestContext
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

class AppSecSCADetectorTest extends DDSpecification {

  @Shared
  protected static final AgentTracer.TracerAPI ORIGINAL_TRACER = AgentTracer.get()

  AgentTracer.TracerAPI tracer
  AgentSpan activeSpan
  AgentSpan rootSpan
  RequestContext reqCtx

  void setup() {
    // Mock the tracer and spans
    rootSpan = Mock(AgentSpan)
    activeSpan = Mock(AgentSpan) {
      getLocalRootSpan() >> rootSpan
    }
    tracer = Mock(AgentTracer.TracerAPI) {
      activeSpan() >> activeSpan
    }
    reqCtx = Mock(RequestContext)

    // Register mock tracer
    AgentTracer.forceRegister(tracer)
  }

  void cleanup() {
    // Restore original tracer
    AgentTracer.forceRegister(ORIGINAL_TRACER)
  }

  def "onMethodInvocation adds tags to root span with advisory and cve"() {
    given:
    String className = "com/example/VulnerableClass"
    String methodName = "vulnerableMethod"
    String descriptor = "()V"
    String advisory = "GHSA-xxxx-yyyy-zzzz"
    String cve = "CVE-2024-0001"

    and:
    rootSpan.getRequestContext() >> reqCtx

    when:
    AppSecSCADetector.onMethodInvocation(className, methodName, descriptor, advisory, cve)

    then:
    1 * rootSpan.setTag("_dd.appsec.sca.class", "com.example.VulnerableClass")
    1 * rootSpan.setTag("_dd.appsec.sca.method", methodName)
    1 * rootSpan.setTag("_dd.appsec.sca.advisory", advisory)
    1 * rootSpan.setTag("_dd.appsec.sca.cve", cve)
    // Note: stack_id may or may not be set depending on whether generateUserCodeStackTrace succeeds
    (0..1) * rootSpan.setTag("_dd.appsec.sca.stack_id", _)
  }

  def "onMethodInvocation adds tags without advisory"() {
    given:
    String className = "com/example/VulnerableClass"
    String methodName = "vulnerableMethod"
    String descriptor = "()V"
    String advisory = null
    String cve = "CVE-2024-0001"

    and:
    rootSpan.getRequestContext() >> reqCtx

    when:
    AppSecSCADetector.onMethodInvocation(className, methodName, descriptor, advisory, cve)

    then:
    1 * rootSpan.setTag("_dd.appsec.sca.class", "com.example.VulnerableClass")
    1 * rootSpan.setTag("_dd.appsec.sca.method", methodName)
    0 * rootSpan.setTag("_dd.appsec.sca.advisory", _)
    1 * rootSpan.setTag("_dd.appsec.sca.cve", cve)
    (0..1) * rootSpan.setTag("_dd.appsec.sca.stack_id", _)
  }

  def "onMethodInvocation adds tags without cve"() {
    given:
    String className = "com/example/VulnerableClass"
    String methodName = "vulnerableMethod"
    String descriptor = "()V"
    String advisory = "GHSA-xxxx-yyyy-zzzz"
    String cve = null

    and:
    rootSpan.getRequestContext() >> reqCtx

    when:
    AppSecSCADetector.onMethodInvocation(className, methodName, descriptor, advisory, cve)

    then:
    1 * rootSpan.setTag("_dd.appsec.sca.class", "com.example.VulnerableClass")
    1 * rootSpan.setTag("_dd.appsec.sca.method", methodName)
    1 * rootSpan.setTag("_dd.appsec.sca.advisory", advisory)
    0 * rootSpan.setTag("_dd.appsec.sca.cve", _)
    (0..1) * rootSpan.setTag("_dd.appsec.sca.stack_id", _)
  }

  def "onMethodInvocation handles no active span gracefully"() {
    given:
    String className = "com/example/VulnerableClass"
    String methodName = "vulnerableMethod"
    String descriptor = "()V"
    String advisory = "GHSA-xxxx-yyyy-zzzz"
    String cve = "CVE-2024-0001"

    and:
    // Mock tracer to return null for activeSpan
    def nullTracer = Mock(AgentTracer.TracerAPI) {
      activeSpan() >> null
    }
    AgentTracer.forceRegister(nullTracer)

    when:
    AppSecSCADetector.onMethodInvocation(className, methodName, descriptor, advisory, cve)

    then:
    notThrown(Exception)
    // No span interactions expected
  }

  def "onMethodInvocation handles no root span gracefully"() {
    given:
    String className = "com/example/VulnerableClass"
    String methodName = "vulnerableMethod"
    String descriptor = "()V"
    String advisory = "GHSA-xxxx-yyyy-zzzz"
    String cve = "CVE-2024-0001"

    and:
    // Mock activeSpan to return null for getLocalRootSpan
    def nullRootActiveSpan = Mock(AgentSpan) {
      getLocalRootSpan() >> null
    }
    def nullRootTracer = Mock(AgentTracer.TracerAPI) {
      activeSpan() >> nullRootActiveSpan
    }
    AgentTracer.forceRegister(nullRootTracer)

    when:
    AppSecSCADetector.onMethodInvocation(className, methodName, descriptor, advisory, cve)

    then:
    notThrown(Exception)
    // No span setTag interactions expected
  }

  def "onMethodInvocation handles no request context gracefully"() {
    given:
    String className = "com/example/VulnerableClass"
    String methodName = "vulnerableMethod"
    String descriptor = "()V"
    String advisory = "GHSA-xxxx-yyyy-zzzz"
    String cve = "CVE-2024-0001"

    and:
    def noReqCtxRootSpan = Mock(AgentSpan) {
      getRequestContext() >> null
    }
    def noReqCtxActiveSpan = Mock(AgentSpan) {
      getLocalRootSpan() >> noReqCtxRootSpan
    }
    def noReqCtxTracer = Mock(AgentTracer.TracerAPI) {
      activeSpan() >> noReqCtxActiveSpan
    }
    AgentTracer.forceRegister(noReqCtxTracer)

    when:
    AppSecSCADetector.onMethodInvocation(className, methodName, descriptor, advisory, cve)

    then:
    notThrown(Exception)
    // Tags should still be set even without request context
    1 * noReqCtxRootSpan.setTag("_dd.appsec.sca.class", "com.example.VulnerableClass")
    1 * noReqCtxRootSpan.setTag("_dd.appsec.sca.method", methodName)
    1 * noReqCtxRootSpan.setTag("_dd.appsec.sca.advisory", advisory)
    1 * noReqCtxRootSpan.setTag("_dd.appsec.sca.cve", cve)
    // Stack ID should not be set if there's no request context
    0 * noReqCtxRootSpan.setTag("_dd.appsec.sca.stack_id", _)
  }

  def "onMethodInvocation converts internal class name to binary name"() {
    given:
    String className = "com/example/nested/VulnerableClass"
    String methodName = "vulnerableMethod"
    String descriptor = "()V"
    String advisory = "GHSA-xxxx-yyyy-zzzz"
    String cve = "CVE-2024-0001"

    and:
    rootSpan.getRequestContext() >> reqCtx

    when:
    AppSecSCADetector.onMethodInvocation(className, methodName, descriptor, advisory, cve)

    then:
    1 * rootSpan.setTag("_dd.appsec.sca.class", "com.example.nested.VulnerableClass")
  }

  def "onMethodInvocation handles exceptions gracefully"() {
    given:
    String className = "com/example/VulnerableClass"
    String methodName = "vulnerableMethod"
    String descriptor = "()V"
    String advisory = "GHSA-xxxx-yyyy-zzzz"
    String cve = "CVE-2024-0001"

    and:
    rootSpan.getRequestContext() >> { throw new RuntimeException("Test exception") }

    when:
    AppSecSCADetector.onMethodInvocation(className, methodName, descriptor, advisory, cve)

    then:
    notThrown(Exception)
  }

  def "onMethodInvocation handles both null advisory and cve"() {
    given:
    String className = "com/example/VulnerableClass"
    String methodName = "vulnerableMethod"
    String descriptor = "()V"
    String advisory = null
    String cve = null

    and:
    rootSpan.getRequestContext() >> reqCtx

    when:
    AppSecSCADetector.onMethodInvocation(className, methodName, descriptor, advisory, cve)

    then:
    1 * rootSpan.setTag("_dd.appsec.sca.class", "com.example.VulnerableClass")
    1 * rootSpan.setTag("_dd.appsec.sca.method", methodName)
    0 * rootSpan.setTag("_dd.appsec.sca.advisory", _)
    0 * rootSpan.setTag("_dd.appsec.sca.cve", _)
    (0..1) * rootSpan.setTag("_dd.appsec.sca.stack_id", _)
  }
}

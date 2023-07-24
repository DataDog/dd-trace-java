package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.Source
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

class TrustBoundaryViolationModuleTest extends IastModuleImplTestBase {
  private List<Object> objectHolder

  private IastRequestContext ctx

  private TrustBoundaryViolationModuleImpl module

  private AgentSpan span


  def setup() {
    InstrumentationBridge.clearIastModules()
    module = registerDependencies(new TrustBoundaryViolationModuleImpl())
    objectHolder = []
    ctx = new IastRequestContext()
    final reqCtx = Mock(RequestContext) {
      getData(RequestContextSlot.IAST) >> ctx
    }
    span = Mock(AgentSpan) {
      getSpanId() >> 123456
      getRequestContext() >> reqCtx
    }
  }

  void 'report TrustBoundary vulnerability for tainted name'() {
    given:
    Vulnerability savedVul
    final name = "name"
    ctx.getTaintedObjects().taintInputString(name, new Source(SourceTypes.NONE, null, null))

    when:
    module.onSessionValue(name, "value")

    then:
    1 * tracer.activeSpan() >> span
    1 * overheadController.consumeQuota(_, _) >> true
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }
    assertVulnerability(savedVul, name)
  }

  void 'report TrustBoundary vulnerability for tainted value'() {
    given:
    Vulnerability savedVul
    final name = "name"
    final badValue = "theValue"
    ctx.getTaintedObjects().taintInputString(badValue, new Source(SourceTypes.NONE, null, null))

    when:
    module.onSessionValue(name, badValue)

    then:
    1 * tracer.activeSpan() >> span
    1 * overheadController.consumeQuota(_, _) >> true
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }
    assertVulnerability(savedVul, badValue)
  }


  void 'report TrustBoundary vulnerability for tainted value within collection'() {
    given:
    Vulnerability savedVul
    final name = "name"
    final badValue = "badValue"
    ctx.getTaintedObjects().taintInputString(badValue, new Source(SourceTypes.NONE, null, null))
    final values = ["A", "B", badValue]

    when:
    module.onSessionValue(name, values)

    then:
    1 * tracer.activeSpan() >> span
    1 * overheadController.consumeQuota(_, _) >> true
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }
    assertVulnerability(savedVul, badValue)
  }

  void 'report TrustBoundary vulnerability for tainted value within array'() {
    given:
    Vulnerability savedVul
    final name = "name"
    final badValue = "badValue"
    ctx.getTaintedObjects().taintInputString(badValue, new Source(SourceTypes.NONE, null, null))
    final values = new String[3]
    values[0] = "A"
    values[1] = "B"
    values[2] = badValue

    when:
    module.onSessionValue(name, values)

    then:
    1 * tracer.activeSpan() >> span
    1 * overheadController.consumeQuota(_, _) >> true
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }
    assertVulnerability(savedVul, badValue)
  }

  void 'report TrustBoundary vulnerability for tainted value within map'() {
    given:
    Vulnerability savedVul
    final name = "name"
    final badValue = "badValue"
    ctx.getTaintedObjects().taintInputString(badValue, new Source(SourceTypes.NONE, null, null))
    final values = new LinkedHashMap<String,String>()
    values.put("A", "A")
    values.put("B", "B")
    values.put("C", badValue)

    when:
    module.onSessionValue(name, values)

    then:
    1 * tracer.activeSpan() >> span
    1 * overheadController.consumeQuota(_, _) >> true
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }
    assertVulnerability(savedVul, badValue)
  }


  private static void assertVulnerability(final Vulnerability vuln, String expectedValue) {
    assert vuln != null
    assert vuln.getType() == VulnerabilityType.TRUST_BOUNDARY_VIOLATION
    assert vuln.getLocation() != null
    assert vuln.getEvidence().getValue() == expectedValue
  }
}

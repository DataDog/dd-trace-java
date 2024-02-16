package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.Reporter
import com.datadog.iast.model.Source
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import com.datadog.iast.taint.Ranges
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import foo.bar.VisitableClass

class TrustBoundaryViolationModuleTest extends IastModuleImplTestBase {

  private TrustBoundaryViolationModuleImpl module

  def setup() {
    InstrumentationBridge.clearIastModules()
    module = new TrustBoundaryViolationModuleImpl(dependencies)
  }

  @Override
  protected Reporter buildReporter() {
    return Mock(Reporter)
  }

  void 'report TrustBoundary vulnerability without context'() {
    when:
    module.onSessionValue('test', 'a value')

    then:
    tracer.activeSpan() >> null
    0 * reporter._
  }

  void 'report TrustBoundary vulnerability for null value'() {
    when:
    module.onSessionValue('test', null)

    then:
    0 * reporter._
  }

  void 'report TrustBoundary vulnerability for tainted name'() {
    given:
    Vulnerability savedVul
    final name = "name"
    ctx.getTaintedObjects().taint(name, Ranges.forCharSequence(name, new Source(SourceTypes.NONE, null, null)))

    when:
    module.onSessionValue(name, "value")

    then:
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }
    assertVulnerability(savedVul, name)
  }

  void 'report TrustBoundary vulnerability for tainted value'() {
    given:
    Vulnerability savedVul
    final name = "name"
    final badValue = "theValue"
    ctx.getTaintedObjects().taint(badValue, Ranges.forCharSequence(badValue, new Source(SourceTypes.NONE, null, null)))

    when:
    module.onSessionValue(name, badValue)

    then:
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }
    assertVulnerability(savedVul, badValue)
  }


  void 'report TrustBoundary vulnerability for tainted value within collection'() {
    given:
    Vulnerability savedVul
    final name = "name"
    final badValue = "badValue"
    ctx.getTaintedObjects().taint(badValue, Ranges.forCharSequence(badValue, new Source(SourceTypes.NONE, null, null)))
    final values = ["A", "B", badValue]

    when:
    module.onSessionValue(name, values)

    then:
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }
    assertVulnerability(savedVul, badValue)
  }

  void 'report TrustBoundary vulnerability for tainted value within array'() {
    given:
    Vulnerability savedVul
    final name = "name"
    final badValue = "badValue"
    ctx.getTaintedObjects().taint(badValue, Ranges.forCharSequence(badValue, new Source(SourceTypes.NONE, null, null)))
    final values = new String[3]
    values[0] = "A"
    values[1] = "B"
    values[2] = badValue

    when:
    module.onSessionValue(name, values)

    then:
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }
    assertVulnerability(savedVul, badValue)
  }

  void 'report TrustBoundary vulnerability for tainted value within map'() {
    given:
    Vulnerability savedVul
    final name = "name"
    final badValue = "badValue"
    ctx.getTaintedObjects().taint(badValue, Ranges.forCharSequence(badValue, new Source(SourceTypes.NONE, null, null)))
    final values = new LinkedHashMap<String,String>()
    values.put("A", "A")
    values.put("B", "B")
    values.put("C", badValue)

    when:
    module.onSessionValue(name, values)

    then:
    1 * reporter.report(_, _ as Vulnerability) >> { savedVul = it[1] }
    assertVulnerability(savedVul, badValue)
  }

  void 'report TrustBoundary vulnerability for tainted value within custom class'() {
    given:
    Vulnerability savedVul
    final name = "name"
    final badValue = "badValue"
    ctx.getTaintedObjects().taint(badValue, Ranges.forCharSequence(badValue, new Source(SourceTypes.NONE, null, null)))
    final value = new VisitableClass(name: badValue)

    when:
    module.onSessionValue(name, value)

    then:
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

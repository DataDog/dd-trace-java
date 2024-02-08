package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.Reporter
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.sink.ReflectionInjectionModule

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat
import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED

class ReflectionInjectionModuleTest extends IastModuleImplTestBase {


  private ReflectionInjectionModule module

  def setup() {
    module = new ReflectionInjectionModuleImpl(dependencies)
  }

  @Override
  protected Reporter buildReporter() {
    return Mock(Reporter)
  }

  void 'iast module detects reflection injection'() {
    setup:
    final tainted = mapTainted(value, mark)

    when:
    module.onReflection(tainted)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertVulnerability(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    value        | mark                                   | expected
    null         | NOT_MARKED                             | null
    '/var'       | NOT_MARKED                             | null
    '/==>var<==' | NOT_MARKED                             | "/==>var<=="
    '/==>var<==' | VulnerabilityMarks.REFLECTION_INJECTION_MARK | null
    '/==>var<==' | VulnerabilityMarks.SQL_INJECTION_MARK  | "/==>var<=="
  }

  private String mapTainted(final String value, int mark) {
    final result = addFromTaintFormat(ctx.taintedObjects, value, mark)
    objectHolder.add(result)
    return result
  }

  private static void assertVulnerability(final Vulnerability vuln, final String expected) {
    assert vuln != null
    assert vuln.getType() == VulnerabilityType.REFLECTION_INJECTION
    assert vuln.getLocation() != null
    final evidence = vuln.getEvidence()
    assert evidence != null
    final formatted = taintFormat(evidence.getValue(), evidence.getRanges())
    assert formatted == expected
  }
}

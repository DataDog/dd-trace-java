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
import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED
import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED
import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED
import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED
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

  void 'iast module detects reflection injection onClassName'() {
    setup:
    final tainted = mapTainted(value, mark)

    when:
    module.onClassName(tainted)

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
    '/==>var<==' | NOT_MARKED                             | '/==>var<=='
    '/==>var<==' | VulnerabilityMarks.REFLECTION_INJECTION_MARK | null
    '/==>var<==' | VulnerabilityMarks.SQL_INJECTION_MARK  | '/==>var<=='
    '/==>var<==' | VulnerabilityMarks.REFLECTION_INJECTION_MARK | null
  }

  void 'iast module detects reflection injection onMethodName'() {
    setup:
    final tainted = mapTainted(value, mark)

    when:
    module.onMethodName(String, tainted, parameterTypes)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertVulnerability(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    value             | parameterTypes  | mark                                   | expected
    null              | null            | NOT_MARKED                             | null
    '/contains'       | String          | NOT_MARKED                             | null
    '/==>contains<==' | String          | NOT_MARKED                             | 'java.lang.String#/==>contains<==(java.lang.String)'
    '/==>contains<==' | String          | VulnerabilityMarks.REFLECTION_INJECTION_MARK | null
    '/==>contains<==' | String          | VulnerabilityMarks.SQL_INJECTION_MARK  | 'java.lang.String#/==>contains<==(java.lang.String)'
    '/==>isEmpty<=='  | null            | NOT_MARKED                             | 'java.lang.String#/==>isEmpty<==()'
    '/==>fake<=='     | [String, null, String] as Class[]  | NOT_MARKED          | 'java.lang.String#/==>fake<==(java.lang.String, UNKNOWN, java.lang.String)'
  }

  void 'iast module detects reflection injection onFieldName'() {
    setup:
    final tainted = mapTainted(value, mark)

    when:
    module.onFieldName(String, tainted)


    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertVulnerability(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    value        | parameterTypes  | mark                                   | expected
    null         | null            | NOT_MARKED                             | null
    '/var'       | String          | NOT_MARKED                             | null
    '/==>var<==' | String          | NOT_MARKED                             | 'java.lang.String#/==>var<=='
    '/==>var<==' | String          | VulnerabilityMarks.REFLECTION_INJECTION_MARK | null
    '/==>var<==' | String          | VulnerabilityMarks.SQL_INJECTION_MARK  | 'java.lang.String#/==>var<=='
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

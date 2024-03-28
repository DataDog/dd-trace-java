package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.Reporter
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.iast.sink.SessionRewritingModule

import static com.datadog.iast.sink.SessionRewritingModuleImpl.EVIDENCE_VALUE

class SessionRewritingModuleTest extends IastModuleImplTestBase {


  private SessionRewritingModule module

  def setup() {
    module = new SessionRewritingModuleImpl(dependencies)
  }

  @Override
  protected Reporter buildReporter() {
    return Mock(Reporter)
  }

  void 'iast module detects session rewriting on sessionTrackingModes'() {
    when:
    module.checkSessionTrackingModes(sessionTrackingModes as Set<String>)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertVulnerability(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    sessionTrackingModes        | expected
    []                          | null
    ['COOKIE']                  | null
    ['URL']                     | EVIDENCE_VALUE
    ['COOKIE', 'URL']           | EVIDENCE_VALUE
  }

  private static void assertVulnerability(final Vulnerability vuln, final String expected) {
    assert vuln != null
    assert vuln.getType() == VulnerabilityType.SESSION_REWRITING
    assert vuln.getLocation() != null
    final evidence = vuln.getEvidence()
    assert evidence.value == expected
  }
}

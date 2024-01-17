package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.Reporter
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.HardcodedSecretModule

class HardcodedSecretModuleTest extends IastModuleImplTestBase {

  private HardcodedSecretModule module

  def setup() {
    module = new HardcodedSecretModuleImpl(dependencies)
    InstrumentationBridge.registerIastModule(module)
  }

  @Override
  protected Reporter buildReporter() {
    return Mock(Reporter)
  }

  void 'check hardcoded secret vulnerability'() {
    given:
    final value = 'value'
    final method = 'method'
    final className = 'className'
    final line = 1

    when:
    module.onHardcodedSecret(value, method, className, line)

    then:
    1 * reporter.report(_, _) >> { assertEvidence(it[1], value, line, className, method) }
  }

  private static void assertVulnerability(final vuln, final expectedVulnType) {
    assert vuln != null
    assert vuln.getType() == expectedVulnType
    assert vuln.getLocation() != null
  }

  private static void assertEvidence(final vuln , final expectedEvidence, final line, final className, final methodName) {
    assertVulnerability(vuln, VulnerabilityType.HARDCODED_SECRET)
    final evidence = vuln.evidence
    assert evidence != null
    assert evidence.value == expectedEvidence
    assert vuln.location.path == className
    assert vuln.location.method == methodName
    assert vuln.location.line == line
  }
}

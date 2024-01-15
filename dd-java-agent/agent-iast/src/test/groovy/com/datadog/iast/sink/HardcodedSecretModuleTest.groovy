package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.Reporter
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.ApplicationModule
import datadog.trace.api.iast.sink.HardcodedSecretModule
import foo.bar.WithHardcodedSecret
import org.apache.commons.io.IOUtils

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
    final classBytes = readClassBytes(clazz)

    when:
    module.onStringLiteral(literals, clazz.getName(), classBytes)

    then:
    if (expectedEvidence != null) {
      1 * reporter.report(_, _) >> { assertEvidence(it[1], expectedEvidence, line, clazz.getName(), method) }
    } else {
      0 * reporter._
    }

    where:
    clazz | literals | expectedEvidence | line | method
    WithHardcodedSecret | [WithHardcodedSecret.FOO, WithHardcodedSecret.getSecret()] as Set | 'age-secret-key' | 8 | 'getSecret'
    SafeClass | [SafeClass.safe] as Set | null | null | null
  }

  class SafeClass{
    static String safe = "this is not a secret"
  }

  byte [] readClassBytes(Class<?> clazz){
    final String classResourceName = clazz.getName().replace('.', '/') + ".class"
    try (InputStream is = clazz.getClassLoader().getResourceAsStream(classResourceName)) {
      if(is == null) {
        throw new IllegalStateException("Could not find class resource: " + classResourceName)
      }
      return IOUtils.toByteArray(is)
    }
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

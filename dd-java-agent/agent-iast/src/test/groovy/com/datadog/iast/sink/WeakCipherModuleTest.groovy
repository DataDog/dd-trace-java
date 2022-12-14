package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.model.Evidence
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.iast.sink.WeakCipherModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

class WeakCipherModuleTest extends IastModuleImplTestBase {

  private WeakCipherModule module

  def setup() {
    module = registerDependencies(new WeakCipherModuleImpl())
  }

  void 'iast module vulnerable cipher algorithm'(final String algorithm){
    given:
    final spanId = 123456
    final span = Mock(AgentSpan)

    when:
    module.onCipherAlgorithm(algorithm)

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getSpanId() >> spanId
    1 * overheadController.consumeQuota(_, _) >> true
    1 * reporter.report(_, _) >> { args ->
      Vulnerability vuln = args[1] as Vulnerability
      assert vuln != null
      assert vuln.getType() == VulnerabilityType.WEAK_CIPHER
      assert vuln.getEvidence() == new Evidence(algorithm)
      assert vuln.getLocation() != null
    }
    0 * _

    where:
    algorithm                     | _
    "DES"                         | _
    "DES/CBC/NoPadding"           | _
    "DESede/CBC/NoPadding"        | _
    "DESede"                      | _
    "PBEWithMD5AndDES"            | _
    "PBEWithMD5AndTripleDES"      | _
    "PBEWithSHA1AndDESede"        | _
    "PBEWithHmacSHA1AndAES_128"   | _
    "PBEWithHmacSHA224AndAES_128" | _
    "PBEWithHmacSHA256AndAES_128" | _
    "PBEWithHmacSHA384AndAES_128" | _
    "PBEWithHmacSHA512AndAES_128" | _
    "PBEWithHmacSHA1AndAES_256"   | _
    "PBEWithHmacSHA224AndAES_256" | _
    "PBEWithHmacSHA256AndAES_256" | _
    "PBEWithHmacSHA384AndAES_256" | _
    "PBEWithHmacSHA512AndAES_256" | _
    "RC2"                         | _
    "Blowfish"                    | _
    "ARCFOUR"                     | _
    "DESedeWrap"                  | _
    "PBEWithSHA1AndRC2_128"       | _
    "PBEWithSHA1AndRC4_40"        | _
    "PBEWithSHA1AndRC4_128"       | _
    "PBEWithHmacSHA1AndAES_128"   | _
  }

  void 'iast module not blocklisted cipher algorithm'(){
    given:
    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span

    when:
    module.onCipherAlgorithm("SecureAlgorithm")

    then:
    0 * _
  }
}

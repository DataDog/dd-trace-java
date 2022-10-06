package com.datadog.iast

import com.datadog.iast.model.Evidence
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.DDId
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

class IastModuleImplHashTest extends IastModuleImplTestBase {

  void 'iast module vulnerable hash algorithm'(){
    given:
    final spanId = DDId.from(123456)
    final span = Mock(AgentSpan)

    when:
    module.onHashingAlgorithm(algorithm)

    then:
    1 * tracer.activeSpan() >> span
    1 * span.getSpanId() >> spanId
    1 * overheadController.consumeQuota(_, _) >> true
    1 * reporter.report(_, _) >> { args ->
      Vulnerability vuln = args[1] as Vulnerability
      assert vuln != null
      assert vuln.getType() == VulnerabilityType.WEAK_HASH
      assert vuln.getEvidence() == new Evidence(algorithm)
      assert vuln.getLocation() != null
    }
    0 * _

    where:
    algorithm | _
    "MD2"     | _
    "MD5"     | _
    "md2"     | _
    "md5"     | _
    "RIPEMD128"     | _
    "MD4"     | _
  }

  void 'iast module called with null argument'(){
    given:
    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span

    when:
    module.onHashingAlgorithm(null)

    then:
    noExceptionThrown()
    0 * _
  }

  void 'iast module secure hash algorithm'(){
    given:
    final span = Mock(AgentSpan)
    tracer.activeSpan() >> span

    when:
    module.onHashingAlgorithm("SHA-256")

    then:
    0 * _
  }
}

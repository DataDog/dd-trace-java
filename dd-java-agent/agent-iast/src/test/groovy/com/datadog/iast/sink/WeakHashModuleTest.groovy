package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.model.Evidence
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.iast.sink.WeakHashModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

class WeakHashModuleTest extends IastModuleImplTestBase {

  private WeakHashModule module

  def setup() {
    module = registerDependencies(new WeakHashModuleImpl())
  }

  void 'iast module vulnerable hash algorithm'(final String algorithm){
    given:
    final spanId = 123456
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

package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.Reporter
import com.datadog.iast.model.Evidence
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.iast.sink.WeakHashModule

class WeakHashModuleTest extends IastModuleImplTestBase {

  private WeakHashModule module

  def setup() {
    module = new WeakHashModuleImpl(dependencies)
  }

  @Override
  protected Reporter buildReporter() {
    return Mock(Reporter)
  }

  void 'iast module vulnerable hash algorithm'(final String algorithm){

    when:
    module.onHashingAlgorithm(algorithm)

    then:
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
    when:
    module.onHashingAlgorithm("SHA-256")

    then:
    0 * _
  }
}

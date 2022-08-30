package test

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.IastModule
import datadog.trace.api.iast.InstrumentationBridge

import java.security.MessageDigest

class WeakHashInstrumentationTest extends AgentTestRunner {

  def "test weak hash instrumentation"() {
    setup:
    IastModule module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    MessageDigest.getInstance(algorithm)

    then:
    1 * module.onHashingAlgorithm(algorithm)

    where:
    algorithm | _
    "MD2"     | _
    "MD5"     | _
    "md2"     | _
    "md5"     | _
  }
}

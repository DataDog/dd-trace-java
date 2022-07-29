package test

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.IASTModule
import datadog.trace.api.iast.IASTModuleInjector

import java.security.MessageDigest

class WeakHasInstrumentationTest extends AgentTestRunner {

  def "test weak hash instrumentation"() {
    setup:
    IASTModule module = Mock(IASTModule)
    IASTModuleInjector.inject(module)

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

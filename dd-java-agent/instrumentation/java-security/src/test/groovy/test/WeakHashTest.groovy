package test

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.IastModule
import datadog.trace.api.iast.InstrumentationBridge

import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class WeakHashTest  extends AgentTestRunner {

  def "unavailable hash algorithm"() {

    when:
    runUnderTrace("WeakHashingRootSpan") {
      MessageDigest.getInstance("SHA-XXX")
    }

    then:
    thrown NoSuchAlgorithmException
  }

  def "test weak hash instrumentation"() {
    setup:
    IastModule module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    MessageDigest.getInstance("MD2")

    then:
    1 * module.onHashingAlgorithm("MD2")
  }
}

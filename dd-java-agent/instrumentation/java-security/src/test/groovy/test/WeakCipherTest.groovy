package test

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.IastModule
import datadog.trace.api.iast.InstrumentationBridge
import foo.bar.TestSuite

import java.security.NoSuchAlgorithmException

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class WeakCipherTest extends AgentTestRunner {

  def "unavailable cipher algorithm"() {

    when:
    runUnderTrace("WeakHashingRootSpan") {
      new TestSuite().getCipherInstance("SHA-XXX")
    }

    then:
    thrown NoSuchAlgorithmException
  }

  def "test weak cipher instrumentation"() {
    setup:
    IastModule module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    new TestSuite().getCipherInstance("DES")

    then:
    1 * module.onCipherAlgorithm(_)
  }

  def "weak cipher instrumentation with null argument"() {
    setup:
    IastModule module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    new TestSuite().getCipherInstance(null)

    then:
    thrown NoSuchAlgorithmException
  }
}

package test

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.IastModule
import datadog.trace.api.iast.InstrumentationBridge

import javax.crypto.Cipher
import java.security.NoSuchAlgorithmException

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class WeakCipherTest  extends AgentTestRunner {

  def "unavailable cipher algorithm"() {

    when:
    runUnderTrace("WeakHashingRootSpan") {
      Cipher.getInstance("SHA-XXX")
    }

    then:
    thrown NoSuchAlgorithmException
  }

  def "test weak cipher instrumentation"() {
    setup:
    IastModule module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    Cipher.getInstance("DES")

    then:
    1 * module.onCipherAlgorithm(_)
  }

  def "weak cipher instrumentation with null argument"() {
    setup:
    IastModule module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    Cipher.getInstance(null)

    then:
    thrown NoSuchAlgorithmException
  }
}

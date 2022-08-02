package test

import datadog.trace.agent.test.AgentTestRunner

import javax.crypto.Cipher
import java.security.NoSuchAlgorithmException

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class UnavailableCipherAlgorithmTest extends AgentTestRunner {

  def "test instrumentation"() {

    when:
    runUnderTrace("WeakHashingRootSpan") {
      Cipher.getInstance("SHA-XXX")
    }

    then:
    thrown NoSuchAlgorithmException
  }
}

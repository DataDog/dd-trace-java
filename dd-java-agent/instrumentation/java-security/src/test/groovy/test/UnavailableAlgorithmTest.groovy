package test

import datadog.trace.agent.test.AgentTestRunner

import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class UnavailableAlgorithmTest extends AgentTestRunner {

  def "test instrumentation"() {

    when:
    runUnderTrace("WeakHashingRootSpan") {
      MessageDigest.getInstance("SHA-XXX")
    }

    then:
    thrown NoSuchAlgorithmException
  }
}

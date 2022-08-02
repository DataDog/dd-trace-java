package test

import datadog.trace.agent.test.AgentTestRunner

import javax.crypto.Cipher

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class SafeCipherTest extends AgentTestRunner {

  def "test instrumentation"() {

    when:
    runUnderTrace("WeakCipherRootSpan") {
      Cipher.getInstance("RSA")
    }

    then:
    assertTraces(1, true) {
      trace(1) {
        span { resourceName "WeakCipherRootSpan" }
      }
    }
  }
}

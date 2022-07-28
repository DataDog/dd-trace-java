package test

import datadog.trace.agent.test.AgentTestRunner

import java.security.MessageDigest

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class SafeHashTest extends AgentTestRunner {

  def "test instrumentation"() {

    when:
    runUnderTrace("WeakHashingRootSpan") {
      MessageDigest.getInstance("SHA-512")
    }

    then:
    assertTraces(1, true) {
      trace(1) {
        span { resourceName "WeakHashingRootSpan" }
      }
    }
  }
}

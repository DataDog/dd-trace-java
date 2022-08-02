package test

import datadog.trace.agent.test.AgentTestRunner

import java.security.MessageDigest

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class BouncyCastleTest  extends AgentTestRunner {
  def 'test bouncycastle'() {
    setup:
    CryptoUtils.loadBouncyCastleProvider()

    when:
    runUnderTrace("WeakHashingRootSpan") {
      MessageDigest.getInstance(algorithm)
    }

    then:
    assertTraces(1, true) {
      trace(2) {
        span { resourceName "WeakHashingRootSpan" }
        span { resourceName "WeakHashingAlgorithm_" + algorithm }
      }
    }

    where:
    algorithm | _
    "RIPEMD128"     | _
    "MD4"     | _
  }
}

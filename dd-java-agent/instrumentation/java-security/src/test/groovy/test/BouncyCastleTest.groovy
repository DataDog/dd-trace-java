package test

import datadog.trace.agent.test.AgentTestRunner
import org.bouncycastle.jce.provider.BouncyCastleProvider

import java.security.MessageDigest
import java.security.Security

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class BouncyCastleTest  extends AgentTestRunner {

  def 'test bouncycastle'() {
    setup:
    Security.addProvider(new BouncyCastleProvider())

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

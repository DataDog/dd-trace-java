package test

import datadog.trace.agent.test.AgentTestRunner

import java.security.MessageDigest

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class WeakHashTest extends AgentTestRunner {

  def "test instrumentation"() {

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
    "MD2"     | _
    "MD5"     | _
    "SHA"     | _
    "SHA1"    | _
    "md2"     | _
    "md5"     | _
    "sha"     | _
    "sha1"    | _
  }
}

package test

import datadog.trace.agent.test.AgentTestRunner

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import javax.crypto.Cipher

class WeakCipherTest extends AgentTestRunner {
  def "test cipher instrumentation"() {

    when:
    runUnderTrace("WeakCipherRootSpan") {
      Cipher.getInstance(algorithm)
    }

    then:
    assertTraces(1, true) {
      trace(2) {
        span { resourceName "WeakCipherRootSpan" }
        span { resourceName "WeakCipherAlgorithm_" + algorithm }
      }
    }

    where:
    algorithm | _
    "DES" | _
    "DESede" | _
    "DESedeWrap" | _
    "PBEWithMD5AndDES" | _
    "PBEWithMD5AndTripleDES" | _
    "PBEWithSHA1AndDESede" | _
    "PBEWithSHA1AndRC2_40" | _
    "PBEWithSHA1AndRC2_128" | _
    "PBEWithSHA1AndRC4_40" | _
    "PBEWithSHA1AndRC4_128" | _
    "PBEWithHmacSHA1AndAES_128" | _
    "PBEWithHmacSHA224AndAES_128" | _
    "PBEWithHmacSHA256AndAES_128" | _
    "PBEWithHmacSHA384AndAES_128" | _
    "PBEWithHmacSHA512AndAES_128" | _
    "PBEWithHmacSHA1AndAES_256" | _
    "PBEWithHmacSHA224AndAES_256" | _
    "PBEWithHmacSHA256AndAES_256" | _
    "PBEWithHmacSHA384AndAES_256" | _
    "PBEWithHmacSHA512AndAES_256" | _
    "RC2" | _
    "Blowfish" | _
    "ARCFOUR" | _
  }
}

package test

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.IastModule
import datadog.trace.api.iast.InstrumentationBridge

import javax.crypto.Cipher

class WeakCipherInstrumentationTest extends AgentTestRunner {

  def "test weak hash instrumentation"() {
    setup:
    IastModule module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    Cipher.getInstance(algorithm)

    then:
    1 * module.onCipherAlgorithm(algorithm)

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

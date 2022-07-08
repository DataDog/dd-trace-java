package test

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.IastModule
import datadog.trace.api.iast.InstrumentationBridge
import org.bouncycastle.jce.provider.BouncyCastleProvider

import java.security.MessageDigest
import java.security.Security

class WeakHashInstrumentationTest extends AgentTestRunner {

  def "test weak hash instrumentation"() {
    setup:
    Security.addProvider(new BouncyCastleProvider())
    IastModule module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    MessageDigest.getInstance(algorithm)

    then:
    1 * module.onHashingAlgorithm(algorithm)

    where:
    algorithm | _
    "MD2"     | _
    "MD5"     | _
    "md2"     | _
    "md5"     | _
    "RIPEMD128"     | _
    "MD4"     | _
  }
}

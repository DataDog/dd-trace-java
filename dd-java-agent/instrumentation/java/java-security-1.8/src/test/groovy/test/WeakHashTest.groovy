package test

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.WeakHashModule
import foo.bar.TestSuite

import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.Provider

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class WeakHashTest extends InstrumentationSpecification {

  def "unavailable hash algorithm"() {

    when:
    runUnderTrace("WeakHashingRootSpan") {
      new TestSuite().getMessageDigestInstance("SHA-XXX")
    }

    then:
    thrown NoSuchAlgorithmException
  }

  def "test weak hash instrumentation"() {
    setup:
    WeakHashModule module = Mock(WeakHashModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    new TestSuite().getMessageDigestInstance("MD2")

    then:
    1 * module.onHashingAlgorithm("MD2")
  }

  def "test weak hash instrumentation with provider"() {
    setup:
    WeakHashModule module = Mock(WeakHashModule)
    InstrumentationBridge.registerIastModule(module)
    final provider = providerFor('MD2')

    when:
    new TestSuite().getMessageDigestInstance('MD2', provider)

    then:
    1 * module.onHashingAlgorithm(_)
  }

  def "test weak hash instrumentation with provider string"() {
    setup:
    WeakHashModule module = Mock(WeakHashModule)
    InstrumentationBridge.registerIastModule(module)
    final provider = providerFor('MD2')

    when:
    new TestSuite().getMessageDigestInstance('MD2', provider.getName())

    then:
    1 * module.onHashingAlgorithm(_)
  }

  private static Provider providerFor(final String algo) {
    final instance = MessageDigest.getInstance(algo)
    return instance.getProvider()
  }
}

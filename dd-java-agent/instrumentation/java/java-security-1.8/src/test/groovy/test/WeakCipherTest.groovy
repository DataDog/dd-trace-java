package test

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.WeakCipherModule
import foo.bar.TestSuite

import javax.crypto.Cipher
import java.security.NoSuchAlgorithmException
import java.security.Provider

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class WeakCipherTest extends InstrumentationSpecification {

  def "unavailable cipher algorithm"() {

    when:
    runUnderTrace("WeakHashingRootSpan") {
      new TestSuite().getCipherInstance("SHA-XXX")
    }

    then:
    thrown NoSuchAlgorithmException
  }

  def "test weak cipher instrumentation"() {
    setup:
    WeakCipherModule module = Mock(WeakCipherModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    new TestSuite().getCipherInstance("DES")

    then:
    1 * module.onCipherAlgorithm(_)
  }

  def "test weak cipher instrumentation with provider"() {
    setup:
    WeakCipherModule module = Mock(WeakCipherModule)
    InstrumentationBridge.registerIastModule(module)
    final provider = providerFor('DES')

    when:
    new TestSuite().getCipherInstance("DES", provider)

    then:
    1 * module.onCipherAlgorithm(_)
  }

  def "test weak cipher instrumentation with provider string"() {
    setup:
    WeakCipherModule module = Mock(WeakCipherModule)
    InstrumentationBridge.registerIastModule(module)
    final provider = providerFor('DES')

    when:
    new TestSuite().getCipherInstance('DES', provider.getName())

    then:
    1 * module.onCipherAlgorithm(_)
  }

  // Key Generator
  def "test weak keygen instrumentation"() {
    setup:
    WeakCipherModule module = Mock(WeakCipherModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    new TestSuite().getKeyGeneratorInstance("DES")

    then:
    1 * module.onCipherAlgorithm(_)
  }

  def "test weak keygen instrumentation with provider"() {
    setup:
    WeakCipherModule module = Mock(WeakCipherModule)
    InstrumentationBridge.registerIastModule(module)
    final provider = providerFor('DES')

    when:
    new TestSuite().getKeyGeneratorInstance("DES", provider)

    then:
    1 * module.onCipherAlgorithm(_)
  }

  def "test weak keygen instrumentation with provider string"() {
    setup:
    WeakCipherModule module = Mock(WeakCipherModule)
    InstrumentationBridge.registerIastModule(module)
    final provider = providerFor('DES')

    when:
    new TestSuite().getKeyGeneratorInstance('DES', provider.getName())

    then:
    1 * module.onCipherAlgorithm(_)
  }

  def "weak cipher instrumentation with null argument"() {
    setup:
    WeakCipherModule module = Mock(WeakCipherModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    new TestSuite().getCipherInstance(null)

    then:
    thrown NoSuchAlgorithmException
  }

  private static Provider providerFor(final String algo) {
    final instance = Cipher.getInstance(algo)
    return instance.getProvider()
  }
}

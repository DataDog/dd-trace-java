package datadog.trace.api.iast


import datadog.trace.test.util.DDSpecification

class InstrumentationBridgeTest extends DDSpecification {

  def "bridge calls module when a new hash is detected"() {
    setup:
    final module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    InstrumentationBridge.onMessageDigestGetInstance('SHA-1')

    then:
    1 * module.onHashingAlgorithm('SHA-1')
  }

  def "bridge calls don't fail with null module when a new hash is detected"() {
    setup:
    InstrumentationBridge.registerIastModule(null)

    when:
    InstrumentationBridge.onMessageDigestGetInstance('SHA-1')

    then:
    noExceptionThrown()
  }

  def "bridge calls don't leak exceptions when a new hash is detected"() {
    setup:
    final module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    InstrumentationBridge.onMessageDigestGetInstance('SHA-1')

    then:
    1 * module.onHashingAlgorithm(_) >> { throw new Error('Boom!!!') }
    noExceptionThrown()
  }

  def "bridge calls module when a new cipher is detected"() {
    setup:
    final module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    InstrumentationBridge.onCipherGetInstance('AES')

    then:
    1 * module.onCipherAlgorithm('AES')
  }

  def "bridge calls don't fail with null module when a new cipher is detected"() {
    setup:
    InstrumentationBridge.registerIastModule(null)

    when:
    InstrumentationBridge.onCipherGetInstance('AES')

    then:
    noExceptionThrown()
  }

  def "bridge calls don't leak exceptions when a new cipher is detected"() {
    setup:
    final module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    InstrumentationBridge.onCipherGetInstance('AES')

    then:
    1 * module.onCipherAlgorithm(_) >> { throw new Error('Boom!!!') }
    noExceptionThrown()
  }
}

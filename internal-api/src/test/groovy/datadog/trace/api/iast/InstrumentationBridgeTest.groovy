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

  def "bridge calls module when a new string concat is detected"() {
    setup:
    final module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    InstrumentationBridge.onStringConcat('Hello ', 'World!', 'Hello World!')

    then:
    1 * module.onStringConcat('Hello ', 'World!', 'Hello World!')
  }

  def "bridge calls don't fail with null module when a string concat is detected"() {
    setup:
    InstrumentationBridge.registerIastModule(null)

    when:
    InstrumentationBridge.onStringConcat('Hello ', 'World!', 'Hello World!')

    then:
    noExceptionThrown()
  }

  def "bridge calls don't leak exceptions when a string concat is detected"() {
    setup:
    final module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    InstrumentationBridge.onStringConcat('Hello ', 'World!', 'Hello World!')

    then:
    1 * module.onStringConcat(_, _, _) >> { throw new Error('Boom!!!') }
    noExceptionThrown()
  }

  def "bridge calls module when a new string builder init is detected"() {
    setup:
    final module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)
    final self = new StringBuilder()

    when:
    InstrumentationBridge.onStringBuilderInit(self, 'test')

    then:
    1 * module.onStringBuilderInit(self, 'test')
  }

  def "bridge calls don't fail with null module when a string builder init is detected"() {
    setup:
    InstrumentationBridge.registerIastModule(null)

    when:
    InstrumentationBridge.onStringBuilderInit(new StringBuilder(), 'test')

    then:
    noExceptionThrown()
  }

  def "bridge calls don't leak exceptions when a string builder init is detected"() {
    setup:
    final module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    InstrumentationBridge.onStringBuilderInit(new StringBuilder(), 'test')

    then:
    1 * module.onStringBuilderInit(_, _) >> { throw new Error('Boom!!!') }
    noExceptionThrown()
  }

  def "bridge calls module when a new string builder append is detected"() {
    setup:
    final module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)
    final self = new StringBuilder()

    when:
    InstrumentationBridge.onStringBuilderAppend(self, 'test')

    then:
    1 * module.onStringBuilderAppend(self, 'test')
  }

  def "bridge calls don't fail with null module when a string builder append is detected"() {
    setup:
    InstrumentationBridge.registerIastModule(null)

    when:
    InstrumentationBridge.onStringBuilderAppend(new StringBuilder(), 'test')

    then:
    noExceptionThrown()
  }

  def "bridge calls don't leak exceptions when a string builder append is detected"() {
    setup:
    final module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    InstrumentationBridge.onStringBuilderAppend(new StringBuilder(), 'test')

    then:
    1 * module.onStringBuilderAppend(_, _) >> { throw new Error('Boom!!!') }
    noExceptionThrown()
  }

  def "bridge calls module when a new string builder toString() is detected"() {
    setup:
    final module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)
    final self = new StringBuilder()

    when:
    InstrumentationBridge.onStringBuilderToString(self, 'test')

    then:
    1 * module.onStringBuilderToString(self, 'test')
  }

  def "bridge calls don't fail with null module when a string builder toString is detected"() {
    setup:
    InstrumentationBridge.registerIastModule(null)

    when:
    InstrumentationBridge.onStringBuilderToString(new StringBuilder('test'), 'test')

    then:
    noExceptionThrown()
  }

  def "bridge calls don't leak exceptions when a string builder toString is detected"() {
    setup:
    final module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    InstrumentationBridge.onStringBuilderToString(new StringBuilder('test'), 'test')

    then:
    1 * module.onStringBuilderToString(_, _) >> { throw new Error('Boom!!!') }
    noExceptionThrown()
  }

  def "bridge calls module when a when a string concat factory call is detected"() {
    setup:
    final module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    InstrumentationBridge.onStringConcatFactory('Hello World!', ['Hello ', 'World!'] as String[], "\u0001\u0001", ["a", "b"] as Object[], [0, 1] as int[])

    then:
    1 * module.onStringConcatFactory(['Hello ', 'World!'] as String[], 'Hello World!', "\u0001\u0001", ["a", "b"] as Object[], [0, 1] as int[])
  }

  def "bridge calls don't fail with null module when a string concat factory call is detected"() {
    setup:
    InstrumentationBridge.registerIastModule(null)

    when:
    InstrumentationBridge.onStringConcatFactory('Hello World!', ['Hello ', 'World!'] as String[], "\u0001\u0001", [] as Object[], [0, 1] as int[])

    then:
    noExceptionThrown()
  }

  def "bridge calls don't leak exceptions when a string concat factory call is detected"() {
    setup:
    final module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    InstrumentationBridge.onStringConcatFactory('Hello World!', ['Hello ', 'World!'] as String[], "\u0001\u0001", [] as Object[], [0, 1] as int[])

    then:
    1 * module.onStringConcatFactory(_, _, _, _, _) >> { throw new Error('Boom!!!') }
    noExceptionThrown()
  }

  def "bridge calls don't leak exceptions for onParameterName and onParameterValue on null parameters"() {
    setup:
    final module = Mock(IastModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    InstrumentationBridge.onParameterValue(null, null)
    InstrumentationBridge.onParameterName()

    then:
    noExceptionThrown()
  }
}

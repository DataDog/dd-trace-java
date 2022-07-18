package datadog.trace.api.iast


import datadog.trace.test.util.DDSpecification

class InstrumentationBridgeTest extends DDSpecification {

  private MockIASTModule module

  def setup() {
    module = InstrumentationBridge.MODULE as MockIASTModule
  }

  def "bridge calls module when a new hash is detected"() {
    setup:
    module.mock = Mock(IASTModule)

    when:
    InstrumentationBridge.onHash('SHA-1')

    then:
    1 * module.mock.onHash('SHA-1')
  }

  def "bridge calls module when a new cipher is detected"() {
    setup:
    module.mock = Mock(IASTModule)

    when:
    InstrumentationBridge.onCipher('AES')

    then:
    1 * module.mock.onCipher('AES')
  }
}

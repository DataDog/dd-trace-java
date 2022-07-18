package datadog.trace.api.iast

import datadog.trace.api.function.Supplier
import datadog.trace.test.util.DDSpecification

class InstrumentationBridgeTest extends DDSpecification {

  private Supplier<IASTModule> defaultModule

  def setup() {
    defaultModule = InstrumentationBridge.MODULE
  }

  def cleanup() {
    InstrumentationBridge.MODULE = defaultModule
  }

  def "bridge calls module when a new hash is detected"() {
    setup:
    final module = Mock(IASTModule)
    InstrumentationBridge.MODULE = { module }

    when:
    InstrumentationBridge.onHash('SHA-1')

    then:
    1 * module.onHash('SHA-1')
  }

  def "bridge calls module when a new cipher is detected"() {
    setup:
    final module = Mock(IASTModule)
    InstrumentationBridge.MODULE = { module }

    when:
    InstrumentationBridge.onCipher('AES')

    then:
    1 * module.onCipher('AES')
  }

  def "current module supplier is not yet implemented"() {
    when:
    InstrumentationBridge.onCipher('AES')

    then:
    thrown UnsupportedOperationException
  }
}

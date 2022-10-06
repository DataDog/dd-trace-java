package datadog.trace.api.iast

import datadog.trace.test.util.DDSpecification
import groovy.transform.Canonical

class InstrumentationBridgeTest extends DDSpecification {
  @Canonical
  static class BridgeMethod {
    String bridgeMethod
    List<Object> params
    String moduleMethod

    String toString() {
      "bridge method $bridgeMethod"
    }
  }

  private final static BRIDGE_METHODS = [
    new BridgeMethod('onCipherGetInstance', ['algo'], 'onCipherAlgorithm'),
    new BridgeMethod('onMessageDigestGetInstance', ['algo'], 'onHashingAlgorithm'),
    new BridgeMethod('onParameterName', ['name'], 'onParameterName'),
    new BridgeMethod('onParameterValue', ['name', 'value'], 'onParameterValue'),
    new BridgeMethod('onStringConcat', ['self', 'param', 'result'], 'onStringConcat'),
    new BridgeMethod('onStringConstructor', [new StringBuilder('foo'), 'result'], 'onStringConstructor'),
    new BridgeMethod('onStringBuilderInit', [new StringBuilder('self'), 'param'], 'onStringBuilderAppend'),
    new BridgeMethod('onStringBuilderAppend', [new StringBuilder('self'), 'param'], 'onStringBuilderAppend'),
    new BridgeMethod('onStringBuilderToString', [new StringBuilder('self'), 'result'], 'onStringBuilderToString'),
  ]

  void '#bridgeMethod does not fail when module is not set'() {
    setup:
    InstrumentationBridge.registerIastModule null

    when:
    InstrumentationBridge."${bridgeMethod.bridgeMethod}"(*bridgeMethod.params)

    then:
    noExceptionThrown()

    where:
    bridgeMethod << BRIDGE_METHODS
  }

  void '#bridgeMethod delegates to the module'() {
    setup:
    def exception
    def module = Mock(IastModule)
    InstrumentationBridge.registerIastModule module

    when:
    InstrumentationBridge."${bridgeMethod.bridgeMethod}"(*bridgeMethod.params)

    then:
    1 * module."${bridgeMethod.moduleMethod}"(*_) >> { List args ->
      try {
        args.size().times { assert args[it].is(bridgeMethod.params[it]) }
      } catch (Throwable t) {
        exception = t
      }
    }
    0 * _
    exception == null

    where:
    bridgeMethod << BRIDGE_METHODS
  }

  void '#bridgeMethod leaks no exceptions'() {
    setup:
    def module = Mock(IastModule)
    InstrumentationBridge.registerIastModule module

    when:
    InstrumentationBridge."${bridgeMethod.bridgeMethod}"(*bridgeMethod.params)

    then:
    1 * module."${bridgeMethod.moduleMethod}"(*_) >> { throw new Throwable('should not leak') }
    0 * _
    noExceptionThrown()

    where:
    bridgeMethod << BRIDGE_METHODS
  }
}

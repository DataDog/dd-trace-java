package datadog.trace.api.iast

import datadog.trace.test.util.DDSpecification
import groovy.transform.Canonical

class InstrumentationBridgeTest extends DDSpecification {

  @Canonical
  static class BridgeMethod {
    String bridgeMethod
    List<Object> params
    String moduleMethod

    @Override
    String toString() {
      "method $bridgeMethod"
    }
  }

  private final static BRIDGE_METHODS = [
    new BridgeMethod('onCipherGetInstance', ['algo'], 'onCipherAlgorithm'),
    new BridgeMethod('onMessageDigestGetInstance', ['algo'], 'onHashingAlgorithm'),
    new BridgeMethod('onParameterName', ['param'], 'onParameterName'),
    new BridgeMethod('onParameterValue', ['param', 'value'], 'onParameterValue'),
    new BridgeMethod('onStringConcat', ['param', 'Value', 'paramValue'], 'onStringConcat'),
    new BridgeMethod('onStringBuilderInit', [new StringBuilder(), 'param'], 'onStringBuilderInit'),
    new BridgeMethod('onStringBuilderAppend', [new StringBuilder(), 'param'], 'onStringBuilderAppend'),
    new BridgeMethod('onStringBuilderToString', [new StringBuilder('param'), 'param'], 'onStringBuilderToString'),
    new BridgeMethod('onStringConcatFactory', [
      'Hello World!',
      ['Hello ', 'World!'] as String[],
      '\u0001\u0001',
      ['a', 'b'] as Object[],
      [0, 1] as int[]
    ], 'onStringConcatFactory'),
    new BridgeMethod('onRuntimeExec', [['ls', '-lah'] as String[]] as List<Object>, 'onRuntimeExec'),
    new BridgeMethod('onProcessBuilderStart', [['ls', '-lah'] as List<String>], 'onProcessBuilderStart'),
    new BridgeMethod('onPathTraversal', ['/var/log'], 'onPathTraversal'),
    new BridgeMethod('onPathTraversal', ['/var', 'log'], 'onPathTraversal'),
    new BridgeMethod('onPathTraversal', ['/var', ['log', 'log.txt'] as String[]], 'onPathTraversal'),
    new BridgeMethod('onPathTraversal', [new File('/var'), '/log/log.txt'], 'onPathTraversal'),
    new BridgeMethod('onPathTraversal', [new URI('file:/tmp')], 'onPathTraversal'),
    new BridgeMethod('onCookie', [['cookieName', 'cookieValue'] as String[]], 'onCookie')
  ]

  void '#bridgeMethod does not fail when module is not set'(final BridgeMethod bridgeMethod) {
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

package datadog.trace.instrumentation.java.net

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.CodecModule
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestURICallSiteSuite

class URICallSIteTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test uri ctor propagation'() {
    given:
    final module = Mock(CodecModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final uri = TestURICallSiteSuite.&"$method".call(args as Object[])

    then:
    uri.toString() == expected
    1 * module.onUriCreate(_ as URI, args as Object[])

    where:
    method | args                                                                          | expected
    'uri'  | ['http://test.com/index?name=value#fragment']                                 | 'http://test.com/index?name=value#fragment'
    'uri'  | ['http', 'user:password', 'test.com', 80, '/index', 'name=value', 'fragment'] | 'http://user:password@test.com:80/index?name=value#fragment'
    'uri'  | ['http', 'user:password@test.com', '/index', 'name=value', 'fragment']        | 'http://user:password@test.com/index?name=value#fragment'
    'uri'  | ['http', 'test.com', '/index', 'fragment']                                    | 'http://test.com/index#fragment'
    'uri'  | ['http', '//test.com/index?name=value', 'fragment']                           | 'http://test.com/index?name=value#fragment'
  }

  void 'test uri create propagation'() {
    given:
    final module = Mock(CodecModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final uri = TestURICallSiteSuite.&"$method".call(args as Object[])

    then:
    uri.toString() == expected
    1 * module.onUriCreate(_ as URI, args[0])

    where:
    method   | args                                          | expected
    'create' | ['http://test.com/index?name=value#fragment'] | 'http://test.com/index?name=value#fragment'
  }

  void 'test uri propagation'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    TestURICallSiteSuite.&"$method".call(args as Object[])

    then:
    1 * module."taint${target}IfTainted"(_, _ as URI, keepRanges, _)

    where:
    method          | target   | args                                                           | keepRanges
    'normalize'     | 'Object' | [new URI('http://test.com/index?name=value#fragment')]         | true
    'normalize'     | 'Object' | [new URI('http://test.com/test/../index?name=value#fragment')] | false
    'toASCIIString' | 'String' | [new URI('http://test.com/index?name=value#fragment')]         | true
    'toASCIIString' | 'String' | [new URI('http://test.com/漢/index?name=value#fragment')]      | false
    'toString'      | 'String' | [new URI('http://test.com/index?name=value#fragment')]         | true
    'toURL'         | 'Object' | [new URI('http://test.com/index?name=value#fragment')]         | true
  }
}

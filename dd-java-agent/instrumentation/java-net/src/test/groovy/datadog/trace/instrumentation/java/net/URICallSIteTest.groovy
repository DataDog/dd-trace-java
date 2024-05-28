package datadog.trace.instrumentation.java.net

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestURICallSiteSuite
import groovy.transform.CompileDynamic

@CompileDynamic
class URICallSIteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test uri ctor propagation'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final uri = TestURICallSiteSuite.&"$method".call(args as Object[])

    then:
    uri.toString() == expected
    1 * module.taintObjectIfAnyTainted(_ as URI, args as Object[])

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
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final uri = TestURICallSiteSuite.&"$method".call(args as Object[])

    then:
    uri.toString() == expected
    1 * module.taintObjectIfTainted(_ as URI, args[0])

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
    1 * module."taint${target}IfTainted"(_, _ as URI)

    where:
    method          | target   | args
    'normalize'     | 'Object' | [new URI('http://test.com/index?name=value#fragment')]
    'toString'      | 'String' | [new URI('http://test.com/index?name=value#fragment')]
    'toASCIIString' | 'String' | [new URI('http://test.com/index?name=value#fragment')]
  }
}

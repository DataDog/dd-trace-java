package datadog.trace.instrumentation.java.net

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.api.iast.sink.SsrfModule
import foo.bar.TestURLCallSiteSuite

class URLCallSiteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test url ctor propagation'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final uri = TestURLCallSiteSuite.&"$method".call(args as Object[])

    then:
    uri.toString() == expected
    1 * module.taintObjectIfAnyTainted(_ as URL, args as Object[])

    where:
    method | args                                                                             | expected
    'url'  | ['http://test.com/index?name=value#fragment']                                    | 'http://test.com/index?name=value#fragment'
    'url'  | ['http', 'test.com', 80, '/index?name=value#fragment']                           | 'http://test.com:80/index?name=value#fragment'
    'url'  | ['http', 'test.com', 80, '/index?name=value#fragment', dummyStreamHandler()]     | 'http://test.com:80/index?name=value#fragment'
    'url'  | ['http', 'test.com', '/index?name=value#fragment']                               | 'http://test.com/index?name=value#fragment'
    'url'  | [new URL('http://test.com'), '/index?name=value#fragment']                       | 'http://test.com/index?name=value#fragment'
    'url'  | [new URL('http://test.com'), '/index?name=value#fragment', dummyStreamHandler()] | 'http://test.com/index?name=value#fragment'
  }

  void 'test url propagation'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    TestURLCallSiteSuite.&"$method".call(args as Object[])

    then:
    1 * module."taint${target}IfTainted"(_, _ as URL)

    where:
    method           | target   | args
    'toURI'          | 'Object' | [new URL('http://test.com/index?name=value#fragment')]
    'toString'       | 'String' | [new URL('http://test.com/index?name=value#fragment')]
    'toExternalForm' | 'String' | [new URL('http://test.com/index?name=value#fragment')]
  }

  void 'test ssrf endpoints'() {
    given:
    final module = Mock(SsrfModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    TestURLCallSiteSuite.&"$method".call(args as Object[])

    then:
    1 * module.onURLConnection(_ as URL)

    where:
    method           | args
    'openConnection' | [URLCallSiteTest.getResource('.')]
    'openConnection' | [URLCallSiteTest.getResource('.'), Proxy.NO_PROXY]
    'openStream'     | [URLCallSiteTest.getResource('.')]
  }

  protected URLStreamHandler dummyStreamHandler() {
    return new URLStreamHandler() {
        @Override
        protected URLConnection openConnection(URL u) throws IOException {
          throw new UnsupportedOperationException()
        }
      }
  }
}

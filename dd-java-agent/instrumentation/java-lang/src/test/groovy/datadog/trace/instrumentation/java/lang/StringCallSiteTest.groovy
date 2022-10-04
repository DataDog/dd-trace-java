package datadog.trace.instrumentation.java.lang

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.IastModule
import datadog.trace.api.iast.InstrumentationBridge
import foo.bar.TestStringSuite

class StringCallSiteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  def 'test string concat call site'() {
    setup:
    final iastModule = Mock(IastModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = TestStringSuite.concat('Hello ', 'World!')

    then:
    result == 'Hello World!'
    1 * iastModule.onStringConcat('Hello ', 'World!', 'Hello World!')
    0 * _
  }

  def 'test string trim call site'() {
    setup:
    final iastModule = Mock(IastModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    final result = TestStringSuite.stringTrim(' hello ')

    then:
    result == 'hello'
    1 * iastModule.onStringTrim(' hello ', 'hello')
    0 * _
  }
}

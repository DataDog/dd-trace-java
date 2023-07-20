package datadog.trace.instrumentation.commonslang

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestStringEscapeUtilsSuite
import groovy.transform.CompileDynamic

@CompileDynamic
class StringEscapeUtilsCallSiteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test #method'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final result = TestStringEscapeUtilsSuite.&"$method".call(args)

    then:
    result == expected
    1 * module.taintAndMarkXSSIfInputIsTainted(_ as String, args[0])
    0 * _

    where:
    method       | args                  | expected
    'escapeHtml' | ['Ø-This is a quote'] | '&Oslash;-This is a quote'
  }
}

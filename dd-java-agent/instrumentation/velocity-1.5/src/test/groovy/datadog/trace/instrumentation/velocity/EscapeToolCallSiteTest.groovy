package datadog.trace.instrumentation.velocity

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestEscapeToolSuite
import groovy.transform.CompileDynamic

import static datadog.trace.api.iast.VulnerabilityMarks.SQL_INJECTION_MARK
import static datadog.trace.api.iast.VulnerabilityMarks.XSS_MARK

@CompileDynamic
class EscapeToolCallSiteTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test #method'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final result = TestEscapeToolSuite.&"$method".call(args)

    then:
    result == expected
    1 * module.taintStringIfTainted(_ as String, args[0], false, mark)
    0 * _

    where:
    method       | args                  | mark               | expected
    'html'       | ['Ø-This is a quote'] | XSS_MARK           | '&Oslash;-This is a quote'
    'javascript' | ['Ø-This is a quote'] | XSS_MARK           | '\\u00D8-This is a quote'
    'url'        | ['Ø-This is a quote'] | XSS_MARK           | '%C3%98-This+is+a+quote'
    'xml'        | ['Ø-This is a quote'] | XSS_MARK           | '&#216;-This is a quote'
    'sql'        | ['Ø-This is a quote'] | SQL_INJECTION_MARK | 'Ø-This is a quote'
  }
}

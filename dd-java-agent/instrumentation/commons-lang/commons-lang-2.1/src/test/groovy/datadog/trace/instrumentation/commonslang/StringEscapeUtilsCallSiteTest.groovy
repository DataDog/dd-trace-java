package datadog.trace.instrumentation.commonslang

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.TestStringEscapeUtilsSuite
import groovy.transform.CompileDynamic

import static datadog.trace.api.iast.VulnerabilityMarks.SQL_INJECTION_MARK
import static datadog.trace.api.iast.VulnerabilityMarks.XSS_MARK
import static datadog.trace.api.iast.VulnerabilityMarks.EMAIL_HTML_INJECTION_MARK

@CompileDynamic
class StringEscapeUtilsCallSiteTest extends InstrumentationSpecification {

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
    1 * module.taintStringIfTainted(_ as String, args[0], false, XSS_MARK | EMAIL_HTML_INJECTION_MARK)
    0 * _

    where:
    method             | args                  | expected
    'escapeHtml'       | ['Ø-This is a quote'] | '&Oslash;-This is a quote'
    'escapeJava'       | ['Ø-This is a quote'] | '\\u00D8-This is a quote'
    'escapeJavaScript' | ['Ø-This is a quote'] | '\\u00D8-This is a quote'
    'escapeXml'        | ['Ø-This is a quote'] | '&#216;-This is a quote'
  }

  void 'test #method sql'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    final result = TestStringEscapeUtilsSuite.&"$method".call(args)

    then:
    result == expected
    1 * module.taintStringIfTainted(_ as String, args[0], false, SQL_INJECTION_MARK)
    0 * _

    where:
    method             | args                  | expected
    'escapeSql'        | ['Ø-This is a quote'] | 'Ø-This is a quote'
  }
}
